/*
// $Id$
// Package org.eigenbase is a class library of data management components.
// Copyright (C) 2005-2006 The Eigenbase Project
// Copyright (C) 2002-2006 Disruptive Tech
// Copyright (C) 2005-2006 LucidEra, Inc.
// Portions Copyright (C) 2003-2006 John V. Sichi
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
package org.eigenbase.rel.rules;

import java.util.*;

import org.eigenbase.rel.*;
import org.eigenbase.relopt.*;
import org.eigenbase.relopt.RelOptUtil.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;

/**
 * PullUpProjectsAboveJoinRule implements the rule for pulling
 * {@link ProjectRel}s beneath a {@link JoinRel} above the {@link JoinRel}.
 * Projections are pulled up if the {@link ProjectRel} doesn't originate from a
 * null generating input in an outer join, and the join condition in the
 * {@link JoinRel} only references projection elements that are
 * {@link RexInputRef}s.
 *
 * @author Zelaine Fong
 * @version $Id$
 */
public class PullUpProjectsAboveJoinRule
    extends RelOptRule
{
    //~ Constructors -----------------------------------------------------------

    public PullUpProjectsAboveJoinRule(RelOptRuleOperand rule, String id)
    {
        super(rule);
        description = "PullUpProjectsAboveJoinRule: " + id;
    }

    //~ Methods ----------------------------------------------------------------

    // implement RelOptRule
    public void onMatch(RelOptRuleCall call)
    {
        JoinRel joinRel = (JoinRel) call.rels[0];
        JoinRelType joinType = joinRel.getJoinType();
        
        ProjectRel leftProj;
        ProjectRel rightProj;
        RexNode [] leftProjExprs = null;
        RexNode [] rightProjExprs = null;
        RelNode leftJoinChild;
        RelNode rightJoinChild;
        // see if at least one input's projection doesn't generate nulls
        if (call.rels[1] instanceof ProjectRel &&
            !joinType.generatesNullsOnLeft())
        {
            leftProj = (ProjectRel) call.rels[1];
            leftProjExprs = leftProj.getProjectExps();
            leftJoinChild = leftProj.getChild();
        } else {
            leftProj = null;
            leftJoinChild = call.rels[1];
        }
        if (call.rels.length == 3 && !joinType.generatesNullsOnRight()) {
            rightProj = (ProjectRel) call.rels[2];
            rightProjExprs = rightProj.getProjectExps();
            rightJoinChild = rightProj.getChild();
        } else {
            rightProj = null;
            rightJoinChild = joinRel.getRight();
        }
        if (leftProj == null && rightProj == null) {
            return;
        }

        // make sure the join condition only references RexInputRefs from
        // the join inputs; otherwise, if we pull up the projection, we end up
        // evaluating the expression once in the join condition and then again
        // in the projection
        int nProjExprs = joinRel.getRowType().getFieldCount();
        BitSet refs = new BitSet(nProjExprs);
        joinRel.getCondition().accept(new InputFinder(refs));
        int nLeft = joinRel.getLeft().getRowType().getFieldCount();
        for (int bit = refs.nextSetBit(0);
            bit >= 0; bit = refs.nextSetBit(bit + 1))
        {
            if (bit < nLeft) {
                if (leftProj != null &&
                    !(leftProjExprs[bit] instanceof RexInputRef))
                {
                    return;
                }
            } else {
                if (rightProj != null &&
                    !(rightProjExprs[bit - nLeft] instanceof RexInputRef))
                {
                    return;
                }
            }
        }
        
        // Construct two RexPrograms and combine them.  The bottom program
        // is a join of the projection expressions from the left and/or
        // right projects that feed into the join.  The top program contains
        // the join condition.
        
        // Create a row type representing a concatentation of the inputs
        // underneath the projects that feed into the join.  This is the input
        // into the bottom RexProgram.  Note that the join type is an inner
        // join because the inputs haven't actually been joined yet.
        RelDataType joinChildrenRowType = 
            JoinRel.deriveJoinRowType(
                leftJoinChild.getRowType(),
                rightJoinChild.getRowType(),
                JoinRelType.INNER,
                joinRel.getCluster().getTypeFactory(),
                null);

        // Create projection expressions, combining the projection expressions
        // from the projects that feed into the join.  For the RHS projection
        // expressions, shift them to the right by the number of fields on
        // the LHS.  If the join input was not a projection, simply create
        // references to the inputs.
        RexNode [] projExprs = new RexNode[nProjExprs];
        String [] fieldNames = new String[nProjExprs];       
        RexBuilder rexBuilder = joinRel.getCluster().getRexBuilder();
        
        createProjectExprs(
            leftProj,
            leftJoinChild,
            0,
            rexBuilder,
            joinChildrenRowType.getFields(),
            projExprs,
            fieldNames,
            0);
        
        RelDataTypeField [] leftFields = leftJoinChild.getRowType().getFields();
        int nFieldsLeft = leftFields.length;
        createProjectExprs(
            rightProj,
            rightJoinChild,
            nFieldsLeft,
            rexBuilder,
            joinChildrenRowType.getFields(),
            projExprs,
            fieldNames,
            (leftProj == null ?
                nFieldsLeft : leftProj.getProjectExps().length));

        RelDataType [] projTypes = new RelDataType[nProjExprs];
        for (int i = 0; i < nProjExprs; i++) {
            projTypes[i] = projExprs[i].getType();
        }
        RelDataType projRowType =
            rexBuilder.getTypeFactory().createStructType(
                projTypes,
                fieldNames);
        
        // create the RexPrograms and merge them
        RexProgram bottomProgram = 
            RexProgram.create(
                joinChildrenRowType,
                projExprs,
                null,
                projRowType,
                rexBuilder);
        RexProgramBuilder topProgramBuilder =
            new RexProgramBuilder(
                projRowType,
                rexBuilder);
        topProgramBuilder.addIdentity();
        topProgramBuilder.addCondition(joinRel.getCondition());
        RexProgram topProgram = topProgramBuilder.getProgram();
        RexProgram mergedProgram =
            RexProgramBuilder.mergePrograms(
                topProgram,
                bottomProgram,
                rexBuilder);

        // expand out the join condition and construct a new JoinRel that
        // directly references the join children without the intervening
        // ProjectRels
        RexNode newCondition =
            mergedProgram.expandLocalRef(
                mergedProgram.getCondition());
        JoinRel newJoinRel =
            new JoinRel(
                joinRel.getCluster(),
                leftJoinChild,
                rightJoinChild,
                newCondition,
                joinRel.getJoinType(),
                joinRel.getVariablesStopped());
        
        // expand out the new projection expressions; if the join is an
        // outer join, modify the expressions to reference the join output
        RexNode [] newProjExprs = new RexNode[nProjExprs];
        List<RexLocalRef> projList = mergedProgram.getProjectList();
        RelDataTypeField [] newJoinFields = newJoinRel.getRowType().getFields();
        int nJoinFields = newJoinFields.length;
        int [] adjustments = new int[nJoinFields];       
        for (int i = 0; i < nProjExprs; i++) {
            RexNode newExpr =
                mergedProgram.expandLocalRef(projList.get(i));
            if (joinType == JoinRelType.INNER) {
                newProjExprs[i] = newExpr;
            } else {
                newProjExprs[i] =
                    newExpr.accept(
                        new RelOptUtil.RexInputConverter(
                            rexBuilder,
                            joinChildrenRowType.getFields(),
                            newJoinFields,
                            adjustments));
            }
        }      
        
        // finally, create the projection on top of the join
        RelNode newProjRel =
            CalcRel.createProject(
                newJoinRel,
                newProjExprs,
                fieldNames);
               
        call.transformTo(newProjRel); 
    }
    
    /**
     * Creates projection expressions corresponding to one of the inputs into
     * the join
     * 
     * @param projRel the projection input into the join (if it exists)
     * @param joinChild the child of the projection input (if there is a
     * projection); otherwise, this is the join input
     * @param adjustmentAmount the amount the expressions need to be shifted
     * by
     * @param rexBuilder rex builder
     * @param joinChildrenFields concatentation of the fields from the left
     * and right join inputs (once the projections have been removed)
     * @param projExprs array of projection expressions to be created
     * @param fieldNames array of the names of the projection fields
     * @param offset starting index in the arrays to be filled in
     */
    private void createProjectExprs(
        ProjectRel projRel,
        RelNode joinChild,
        int adjustmentAmount,
        RexBuilder rexBuilder,
        RelDataTypeField [] joinChildrenFields,
        RexNode [] projExprs,
        String [] fieldNames,
        int offset)
    {
        RelDataTypeField [] childFields =
            joinChild.getRowType().getFields();
        if (projRel != null) {
            RexNode [] origProjExprs = projRel.getProjectExps();           
            RelDataTypeField [] projFields =
                projRel.getRowType().getFields();
            int nChildFields = childFields.length;
            int [] adjustments = new int[nChildFields];
            for (int i = 0; i < nChildFields; i++) {
                adjustments[i] = adjustmentAmount;
            }            
            for (int i = 0; i < origProjExprs.length; i++) {
                if (adjustmentAmount == 0) {
                    projExprs[i + offset] = origProjExprs[i];
                } else {
                    // shift the references by the adjustment amount
                    RexNode newProjExpr =
                        origProjExprs[i].accept(
                            new RelOptUtil.RexInputConverter(
                                rexBuilder,
                                childFields,
                                joinChildrenFields,
                                adjustments));
                    projExprs[i + offset] = newProjExpr;     
                }
                fieldNames[i + offset] = projFields[i].getName();
            }
        } else {
            // no projection; just create references to the inputs
            for (int i = 0; i < childFields.length; i++)
            {
                projExprs[i + offset] =
                    rexBuilder.makeInputRef(
                        childFields[i].getType(),
                        i + adjustmentAmount);
                fieldNames[i + offset] = childFields[i].getName();
            }
        }       
    }
}

// End PullUpProjectsAboveJoinRule.java