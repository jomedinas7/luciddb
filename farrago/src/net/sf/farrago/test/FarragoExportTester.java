/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005-2005 The Eigenbase Project
// Copyright (C) 2005-2005 Disruptive Tech
// Copyright (C) 2005-2005 Red Square, Inc.
// Portions Copyright (C) 2004-2005 John V. Sichi
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
package net.sf.farrago.test;

import java.io.*;
import java.util.*;
import org.netbeans.api.xmi.*;
import org.netbeans.api.mdr.*;
import org.netbeans.mdr.*;
import org.netbeans.mdr.persistence.*;
import org.netbeans.mdr.storagemodel.*;
import net.sf.farrago.util.*;
import net.sf.farrago.catalog.*;
import net.sf.farrago.*;
import javax.jmi.reflect.*;
import javax.jmi.model.*;
import javax.jmi.xmi.*;
import junit.framework.*;

/**
 * FarragoExportTester tests XMI export from the Farrago repository.
 * It doesn't run as a normal test because it has a destructive effect
 * on the repository.
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class FarragoExportTester extends FarragoTestCase
{
    private ExportFixture exportFixture;

    private static boolean cleaned;
    
    public FarragoExportTester(String testName)
        throws Exception
    {
        super(testName);
    }

    // implement TestCase
    public static Test suite()
    {
        return wrappedSuite(FarragoExportTester.class);
    }

    // implement TestCase
    public void setUp()
        throws Exception
    {
        super.setUp();

        exportFixture = new ExportFixture();
        if (!cleaned) {
            // clean out generated data from previous runs
            FarragoFileAllocation dirAlloc =
                new FarragoFileAllocation(exportFixture.testdataDir);
            dirAlloc.closeAllocation();
            exportFixture.testdataDir.mkdirs();
            cleaned = true;
        }
    }

    /**
     * Tests export of a single object (the SALES.DEPTS table).
     */
    public void testObjectExport()
        throws Exception
    {
        XMIWriter xmiWriter = XMIWriterFactory.getDefault().createXMIWriter();
        FileOutputStream outStream = new FileOutputStream(
            new File(exportFixture.testdataDir, "depts.xmi"));
        RefObject depts = 
            FarragoCatalogUtil.getModelElementByName(
                FarragoCatalogUtil.getSchemaByName(
                    repos, 
                    repos.getSelfAsCatalog(),
                    "SALES").getOwnedElement(),
                "DEPTS");
        try {
            xmiWriter.getConfiguration().setReferenceProvider(
                new XRP(depts));
            xmiWriter.write(
                outStream,
                "DEPTS",
                repos.getMdrRepos().getExtent("FarragoCatalog"),
                "1.2");
        } finally {
            outStream.close();
        }
    }

    private class XRP implements XMIReferenceProvider 
    {
        private final RefObject root;
        
        XRP(RefObject root)
        {
            this.root = root;
        }
        
        public XMIReferenceProvider.XMIReference getReference(RefObject obj)
        {
            RefObject parent = obj;
            do {
                if (parent == root) {
                    return new XMIReferenceProvider.XMIReference(
                        "DEPTS", obj.refMofId());
                }
                parent = (RefObject) parent.refImmediateComposite();
            } while (parent != null);
            return new XMIReferenceProvider.XMIReference(
                "REPOS", obj.refMofId());
        }
    }
    
    /**
     * Tests the sequence export+drop.
     */
    public void testExportAndDrop()
        throws Exception
    {
        runExport();
        runDeletion();
    }
    
    /**
     * Tests full-catalog XMI export.
     */
    private void runExport()
        throws Exception
    {
        // perform exports
        exportXmi(
            repos.getMdrRepos(),
            exportFixture.metamodelFile,
            "FarragoMetamodel");
        exportXmi(
            repos.getMdrRepos(),
            exportFixture.catalogFile,
            "FarragoCatalog");
    }

    /**
     * Tests repository deletion.
     */
    private void runDeletion()
        throws Exception
    {
        // shut down repository
        forceShutdown();

        FarragoModelLoader modelLoader = new FarragoModelLoader();
        try {
            // grotty internals for dropping physical repos storage
            FarragoPackage farragoPackage = modelLoader.loadModel(
                "FarragoCatalog", false);
            String mofIdString = farragoPackage.refMofId();
            MOFID mofId = MOFID.fromString(mofIdString);
        
            NBMDRepositoryImpl reposImpl = (NBMDRepositoryImpl)
                modelLoader.getMdrRepos();
            Storage storage =
                reposImpl.getMdrStorage().getStorageByMofId(mofId);
            storage.close();
            storage.delete();
        } finally {
            modelLoader.close();
        }
    }
    
    private void exportXmi(
        MDRepository mdrRepos,
        File file,
        String extentName)
        throws Exception
    {
        RefPackage refPackage = mdrRepos.getExtent(extentName);
        XmiWriter xmiWriter = XMIWriterFactory.getDefault().createXMIWriter();
        FileOutputStream outStream = new FileOutputStream(file);
        try {
            xmiWriter.write(outStream, refPackage, "1.2");
        } finally {
            outStream.close();
        }
    }

    static class ExportFixture 
    {
        File testdataDir;
        
        File metamodelFile;
    
        File catalogFile;

        ExportFixture()
        {
            // define a private directory for generated datafiles
            String homeDir = FarragoProperties.instance().homeDir.get();
            testdataDir = new File(homeDir, "testgen");
            testdataDir = new File(testdataDir, "FarragoExportTester");
            testdataDir = new File(testdataDir, "data");
            metamodelFile = new File(testdataDir, "metamodel.xmi");
            catalogFile = new File(testdataDir, "catalog.xmi");
        }
    }
}

// End FarragoExportTester.java