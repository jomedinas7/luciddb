/*
// $Id$
// Fennel is a library of data storage and processing components.
// Copyright (C) 2006-2006 LucidEra, Inc.
// Copyright (C) 2006-2006 The Eigenbase Project
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

#include "fennel/common/CommonPreamble.h"
#include "fennel/exec/ExecStreamBufAccessor.h"
#include "fennel/lucidera/bitmap/LbmUnionExecStream.h"

#include <math.h>

FENNEL_BEGIN_CPPFILE("$Id$");

LbmUnionExecStream::LbmUnionExecStream()
{
    ridLimitParamId = DynamicParamId(0);
    startRidParamId = DynamicParamId(0);
    segmentLimitParamId = DynamicParamId(0);
}

void LbmUnionExecStream::prepare(LbmUnionExecStreamParams const &params)
{
    ConfluenceExecStream::prepare(params);
    maxRid = params.maxRid;
    
    // set dynanmic parameter ids
    ridLimitParamId = params.ridLimitParamId;
    assert(opaqueToInt(ridLimitParamId) > 0);

    // optional parameters
    startRidParamId = params.startRidParamId;
    segmentLimitParamId = params.segmentLimitParamId;

    // setup tupledatums for writing dynamic parameter values
    ridLimitDatum.pData = (PConstBuffer) &ridLimit;
    ridLimitDatum.cbData = sizeof(ridLimit);

    assert(inAccessors[0]->getTupleDesc() == pOutAccessor->getTupleDesc());

    // initialize reader
    inputTuple.compute(inAccessors[0]->getTupleDesc());
    segmentReader.init(inAccessors[0], inputTuple);

    // output buffer will come from scratch segment
    scratchAccessor = params.scratchAccessor;
    workspacePageLock.accessSegment(scratchAccessor);
    writerPageLock.accessSegment(scratchAccessor);
    pageSize = scratchAccessor.pSegment->getUsablePageSize();
}

void LbmUnionExecStream::getResourceRequirements(
    ExecStreamResourceQuantity &minQuantity,
    ExecStreamResourceQuantity &optQuantity)
{
    ConfluenceExecStream::getResourceRequirements(minQuantity, optQuantity);

    // at least 2 scratch pages for constructing output bitmap segments
    //   - 1 for workspace
    //   - 1 for writer
    minQuantity.nCachePages += 2;
    optQuantity.nCachePages += computeOptWorkspacePages(maxRid) + 1;
}

void LbmUnionExecStream::setResourceAllocation(
    ExecStreamResourceQuantity &quantity)
{
    ConfluenceExecStream::setResourceAllocation(quantity);

    // TODO: can we just grab all the remaining pages like this?
    nWorkspacePages = quantity.nCachePages;
    ridLimit = computeRidLimit(nWorkspacePages);
}

void LbmUnionExecStream::open(bool restart)
{
    ConfluenceExecStream::open(restart);

    if (!restart) {
        // TODO: get the max writer buffer size from some segment library
        uint writerBufSize = scratchAccessor.pSegment->getUsablePageSize()/8;
        writerPageLock.allocatePage();
        PBuffer writerBuf = workspacePageLock.getPage().getWritableData();
        segmentWriter.init(
            writerBuf, writerBufSize, pOutAccessor->getTupleDesc());

        // allocate byte buffer for merging segments
        boost::shared_array<PBuffer> ppBuffers(new PBuffer[nWorkspacePages]);
        assert(ppBuffers != NULL);
        for (uint i = 0; i < nWorkspacePages; i++) {
            workspacePageLock.allocatePage();
            ppBuffers[i] = workspacePageLock.getPage().getWritableData();
        }
        VirtualByteBuffer *pVirtualBuffer = new VirtualByteBuffer();
        pVirtualBuffer->init(ppBuffers, nWorkspacePages, pageSize);
        SharedByteBuffer pWorkspaceBuffer(pVirtualBuffer);
        uint maxSegmentSize = writerBufSize;
        workspace.init(pWorkspaceBuffer, maxSegmentSize);

        // create dynamic parameters
        pDynamicParamManager->createParam(
            ridLimitParamId, pOutAccessor->getTupleDesc()[0]);
        pDynamicParamManager->writeParam(ridLimitParamId, ridLimitDatum);
    } else {
        workspace.reset();
        segmentWriter.reset();
    }

    writePending = false;
    producePending = false;
    isDone = false;
}

ExecStreamResult LbmUnionExecStream::execute(
    ExecStreamQuantum const &quantum)
{
     if (isDone) {
        pOutAccessor->markEOS();
        return EXECRC_EOS;
     }

     if (isConsumerSridSet()) {
         requestedSrid = (LcsRid) *reinterpret_cast<RecordNum const *>(
             pDynamicParamManager->getParam(startRidParamId).getDatum().pData);
     }
     if (isSegmentLimitSet()) {
         segmentsRemaining = *reinterpret_cast<uint const *>(
             pDynamicParamManager->getParam(segmentLimitParamId)
             .getDatum().pData);
     }

     for (uint i = 0; i < quantum.nTuplesMax; i++) {
        while (! producePending) {
            ExecStreamResult status = readSegment();
            if (status == EXECRC_EOS) {
                // flush any remaining data as last tuple(s)
                if (! workspace.isEmpty()) {
                    transferLast();
                    producePending = true;
                    break;
                }
                isDone = true;
                return EXECRC_BUF_OVERFLOW;
            }
            if (status != EXECRC_YIELD) {
                return status;
            }
            if (! writeSegment()) {
                producePending = true;
            }
        }

        if (! produceTuple()) {
            return EXECRC_BUF_OVERFLOW;
        }
        producePending = false;
    }
    return EXECRC_QUANTUM_EXPIRED;
}

void LbmUnionExecStream::closeImpl()
{
    ConfluenceExecStream::closeImpl();
    pDynamicParamManager->deleteParam(ridLimitParamId);
}

uint LbmUnionExecStream::computeOptWorkspacePages(LcsRid maxRid) 
{
    LcsRid bytes = (maxRid / LbmSegment::LbmOneByteSize) + 1;
    // save half a page for building segments
    bytes += pageSize / 2;
    double pages = ((double) opaqueToInt(bytes)) / pageSize;
    return (uint) ceil(pages);
}

uint LbmUnionExecStream::computeRidLimit(uint nWorkspacePages)
{
    // save half a page for building segments
    uint bytes = (uint) ((nWorkspacePages - 0.5) * pageSize);
    return bytes * LbmSegment::LbmOneByteSize;
}

bool LbmUnionExecStream::isConsumerSridSet()
{
    return (opaqueToInt(startRidParamId) > 0);
}

bool LbmUnionExecStream::isSegmentLimitSet()
{
    return (opaqueToInt(segmentLimitParamId) > 0);
}

ExecStreamResult LbmUnionExecStream::readSegment()
{
    if (writePending) {
        return EXECRC_YIELD;
    }
    ExecStreamResult status = segmentReader.readSegmentAndAdvance(
        inputSegment.byteNum, inputSegment.byteSeg, inputSegment.len);
    if (status == EXECRC_YIELD) {
        writePending = true;
    }
    return status;
}

bool LbmUnionExecStream::writeSegment()
{
    assert(writePending = true);

    // as an optimization, signal workspace to avoid Rids not 
    // required by a downstream INTERSECT 
    if (isConsumerSridSet()) {
        workspace.advanceToSrid(requestedSrid);
    }

    // eagerly flush segments
    LcsRid currentSrid = segmentReader.getSrid();
    workspace.setProductionLimit(currentSrid - 1);
    if (!transfer()) {
        return false;
    }

    // flushing the workspace should make enough room for the next tuple
    assert(workspace.addSegment(inputSegment));
    writePending = false;
    return true;
}

void LbmUnionExecStream::transferLast()
{
    workspace.removeLimit();
    transfer();
}

bool LbmUnionExecStream::transfer()
{
    while (workspace.canProduce()) {
        if (isSegmentLimitSet() && segmentsRemaining == 0) {
            return false;
        }

        const LbmByteSegment &seg = workspace.getSegment();
        if (! segmentWriter.addSegment(seg.getSrid(), seg.byteSeg, seg.len)) {
            return false;
        }
        workspace.advancePastSegment();

        if (isSegmentLimitSet()) {
            segmentsRemaining--;
        }
    }
    return true;
}

bool LbmUnionExecStream::produceTuple()
{
    assert(producePending);

    outputTuple = segmentWriter.produceSegmentTuple();
    if (pOutAccessor->produceTuple(outputTuple)) {
        producePending = false;
        return true;
    }
    return false;
}

FENNEL_END_CPPFILE("$Id$");

// End LbmUnionExecStream.cpp