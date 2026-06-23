/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xwiki.contrib.pagepropertylinks.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.pagepropertylinks.PagePropertyLinksConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.StringListProperty;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PageClass;
import com.xpn.xwiki.objects.classes.StringClass;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultPagePropertyReferenceReader}.
 * Uses {@code @OldcoreTest} because this touches {@link XWikiDocument}/{@link BaseObject}/
 * {@link com.xpn.xwiki.objects.classes.PropertyClass} and the document's XClass lookup via context.
 */
@OldcoreTest
@ReferenceComponentList
class DefaultPagePropertyReferenceReaderTest
{
    /** The document reference used as home for all test documents. */
    private static final DocumentReference HOLDER_REF =
        new DocumentReference("xwiki", "TestSpace", "TestDoc");

    /** The class reference used for the test XClass. */
    private static final DocumentReference CLASS_REF =
        new DocumentReference("xwiki", "TestSpace", "TestClass");

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultPagePropertyReferenceReader reader;

    @MockComponent
    private PagePropertyLinksConfiguration configuration;

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void readsSingleValuePageProperty() throws Exception
    {
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);

        XWikiDocument doc = buildDocWithSingleValuePageProp("Departments.Accounting.WebHome");
        List<DocumentReference> refs = this.reader.getReferences(doc, this.oldcore.getXWikiContext());

        DocumentReference expected = new DocumentReference("xwiki",
            Arrays.asList("Departments", "Accounting"), "WebHome");
        assertEquals(1, refs.size());
        assertEquals(expected, refs.get(0));
    }

    @Test
    void readsRelationalMultiValuePageProperty() throws Exception
    {
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);

        XWikiDocument doc = buildDocWithRelationalMultiValuePageProp(
            Arrays.asList("Departments.Accounting.WebHome", "Departments.Sales.WebHome"));
        List<DocumentReference> refs = this.reader.getReferences(doc, this.oldcore.getXWikiContext());

        DocumentReference expectedAccounting = new DocumentReference("xwiki",
            Arrays.asList("Departments", "Accounting"), "WebHome");
        DocumentReference expectedSales = new DocumentReference("xwiki",
            Arrays.asList("Departments", "Sales"), "WebHome");
        assertEquals(2, refs.size());
        assertTrue(refs.contains(expectedAccounting));
        assertTrue(refs.contains(expectedSales));
    }

    @Test
    void ignoresNonPageProperty() throws Exception
    {
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);

        XWikiDocument doc = buildDocWithNonPageProp("Departments.Accounting.WebHome");
        assertTrue(this.reader.getReferences(doc, this.oldcore.getXWikiContext()).isEmpty());
    }

    @Test
    void skipsNonRelationalWhenFlagOff() throws Exception
    {
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);

        XWikiDocument doc = buildDocWithNonRelationalMultiValuePageProp(
            Arrays.asList("Departments.Accounting.WebHome"));
        assertTrue(this.reader.getReferences(doc, this.oldcore.getXWikiContext()).isEmpty());
    }

    @Test
    void readsNonRelationalWhenFlagOn() throws Exception
    {
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(true);

        XWikiDocument doc = buildDocWithNonRelationalMultiValuePageProp(
            Arrays.asList("Departments.Accounting.WebHome"));
        List<DocumentReference> refs = this.reader.getReferences(doc, this.oldcore.getXWikiContext());

        DocumentReference expected = new DocumentReference("xwiki",
            Arrays.asList("Departments", "Accounting"), "WebHome");
        assertEquals(1, refs.size());
        assertEquals(expected, refs.get(0));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a document that has one BaseObject whose XClass has a {@link PageClass}-backed field
     * named "ref", with a single {@link StringProperty} holding the given local reference string.
     * This simulates a single-value Page property (scalar storage).
     *
     * <p>The document is saved via {@code getSpyXWiki().saveDocument()} so that the XClass is
     * registered in the in-memory store and {@code object.getXClass(context)} can resolve it.
     */
    private XWikiDocument buildDocWithSingleValuePageProp(String storedLocalRef) throws Exception
    {
        // Build the class document that declares the XClass
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        PageClass pageClassDef = new PageClass();
        pageClassDef.setName("ref");
        xclass.put("ref", pageClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        // Build the holder document and add an object of that class
        XWikiDocument doc = new XWikiDocument(HOLDER_REF);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        StringProperty prop = new StringProperty();
        prop.setName("ref");
        prop.setValue(storedLocalRef);
        obj.safeput("ref", prop);

        doc.addXObject(obj);
        return doc;
    }

    /**
     * Builds a document that has one BaseObject whose XClass has a {@link PageClass}-backed field
     * named "refs", with a {@link DBStringListProperty} holding the given local reference strings.
     * This simulates a multi-value relational Page property.
     */
    private XWikiDocument buildDocWithRelationalMultiValuePageProp(List<String> storedLocalRefs)
        throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        PageClass pageClassDef = new PageClass();
        pageClassDef.setName("refs");
        xclass.put("refs", pageClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        XWikiDocument doc = new XWikiDocument(HOLDER_REF);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        DBStringListProperty prop = new DBStringListProperty();
        prop.setName("refs");
        prop.setList(new ArrayList<>(storedLocalRefs));
        obj.safeput("refs", prop);

        doc.addXObject(obj);
        return doc;
    }

    /**
     * Builds a document that has one BaseObject whose XClass has a {@link PageClass}-backed field
     * named "refs", with a {@link StringListProperty} holding the given local reference strings.
     * This simulates a multi-value non-relational Page property (multiselect, not relational storage).
     * {@link StringListProperty} is a sibling of {@link DBStringListProperty}; both extend
     * {@code ListProperty} but {@code StringListProperty} is NOT a {@code DBStringListProperty}.
     */
    private XWikiDocument buildDocWithNonRelationalMultiValuePageProp(List<String> storedLocalRefs)
        throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        PageClass pageClassDef = new PageClass();
        pageClassDef.setName("refs");
        xclass.put("refs", pageClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        XWikiDocument doc = new XWikiDocument(HOLDER_REF);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        StringListProperty prop = new StringListProperty();
        prop.setName("refs");
        prop.setList(new ArrayList<>(storedLocalRefs));
        obj.safeput("refs", prop);

        doc.addXObject(obj);
        return doc;
    }

    /**
     * Builds a document that has one BaseObject whose XClass has a {@link StringClass}-backed field
     * named "nonPageRef". The stored value looks like a page reference but the property class type
     * is "String", not "Page" — so the type gate must exclude it.
     */
    private XWikiDocument buildDocWithNonPageProp(String storedLocalRef) throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        StringClass stringClassDef = new StringClass();
        stringClassDef.setName("nonPageRef");
        xclass.put("nonPageRef", stringClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        XWikiDocument doc = new XWikiDocument(HOLDER_REF);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        StringProperty prop = new StringProperty();
        prop.setName("nonPageRef");
        prop.setValue(storedLocalRef);
        obj.safeput("nonPageRef", prop);

        doc.addXObject(obj);
        return doc;
    }
}
