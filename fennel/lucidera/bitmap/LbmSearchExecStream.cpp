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
#include "fennel/tuple/StandardTypeDescriptor.h"
#include "fennel/exec/ExecStreamBufAccessor.h"
#include "fennel/lucidera/colstore/LcsClusterNode.h"
#include "fennel/lucidera/bitmap/LbmSearchExecStream.h"

FENNEL_BEGIN_CPPFILE("$Id$");

void LbmSearchExecStream::prepare(LbmSearchExecStreamParams const &params)
{
    BTreeSearchExecStream::prepare(params);

    rowLimitParamId = params.rowLimitParamId;
    ignoreRowLimit = (rowLimitParamId == DynamicParamId(0));
    if (!ignoreRowLimit) {
        // tupledatum for dynamic parameter
        rowLimitDatum.pData = (PConstBuffer) &rowLimit;
        rowLimitDatum.cbData = sizeof(rowLimit);
    }

    startRidParamId = params.startRidParamId;
    ridInKey = (startRidParamId > DynamicParamId(0));
    if (ridInKey) {

        startRidDatum.pData = (PConstBuffer) &startRid;
        startRidDatum.cbData = sizeof(startRid);

        // add on the rid to the btree search key if the key hasn't already
        // been setup
        TupleDescriptor ridKeyDesc = inputKeyDesc;
        if (inputKeyDesc.size() == treeDescriptor.keyProjection.size() - 1) {

            StandardTypeDescriptorFactory stdTypeFactory;
            TupleAttributeDescriptor attrDesc(
                stdTypeFactory.newDataType(STANDARD_TYPE_RECORDNUM));
            ridKeyDesc.push_back(attrDesc);
            ridKeySetup = false;
        } else {
            assert(
                inputKeyDesc.size() == 1 &&
                inputKeyDesc.size() == treeDescriptor.keyProjection.size());
            ridKeySetup = true;
        }

        ridSearchKeyData.compute(ridKeyDesc);
        // rid is last key
        ridSearchKeyData[ridSearchKeyData.size() - 1].pData =
            (PConstBuffer) &startRid;

        // need to look for greatest lower bound if searching on rid
        leastUpper = false;
    }
}

bool LbmSearchExecStream::reachedTupleLimit(uint nTuples)
{
    if (ignoreRowLimit) {
        return false;
    }

    // read the parameter the first time through
    if (nTuples == 0) {
        pDynamicParamManager->readParam(rowLimitParamId, rowLimitDatum);
    }
    // a row limit of 0 indicates that the scan should read till EOS
    if (rowLimit == 0) {
        return false;
    }
    return (nTuples >= rowLimit);
}

void LbmSearchExecStream::setAdditionalKeys()
{
    if (ridInKey) {
        // If the rid key was not setup in farrago, need to copy the keys
        // that precede the rid.  Also make sure that in this case, the search
        // is an equality one.  Otherwise, in the case where the key was setup,
        // the search is a greater than equal search.
        assert(lowerBoundDirective == SEARCH_CLOSED_LOWER);
        if (ridKeySetup) {
            assert(upperBoundDirective == SEARCH_UNBOUNDED_UPPER);
        } else {
            assert(upperBoundDirective == SEARCH_CLOSED_UPPER);

            for (uint i = 0; i < inputKeyData.size(); i++) {
                ridSearchKeyData[i] = inputKeyData[i];
            }
        }
        pDynamicParamManager->readParam(startRidParamId, startRidDatum);
        pSearchKey = &ridSearchKeyData;

    } else {
        pSearchKey = &inputKeyData;
    }
}

FENNEL_END_CPPFILE("$Id$");

// End LbmSearchExecStream.cpp