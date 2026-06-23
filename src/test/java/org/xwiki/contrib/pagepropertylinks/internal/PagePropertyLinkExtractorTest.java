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

import java.util.Collection;
import java.util.List;

import javax.inject.Provider;

import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.pagepropertylinks.PagePropertyReferenceReader;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.search.solr.internal.api.FieldUtils;
import org.xwiki.search.solr.internal.metadata.SolrLinkSerializer;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PagePropertyLinkExtractor}.
 *
 * <p>Uses {@code @ComponentTest} (not {@code @OldcoreTest}) because this component only calls
 * {@link PagePropertyReferenceReader} and {@link SolrLinkSerializer}, both of which are mocked.
 * The {@link Provider}{@code <XWikiContext>} is also mocked — no real oldcore plumbing needed.
 */
@ComponentTest
class PagePropertyLinkExtractorTest
{
    @InjectMockComponents
    private PagePropertyLinkExtractor extractor;

    @MockComponent
    private PagePropertyReferenceReader reader;

    @MockComponent
    private SolrLinkSerializer linkSerializer;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        this.context = mock(XWikiContext.class);
        when(this.contextProvider.get()).thenReturn(this.context);
    }

    @Test
    void addsLinksAndReturnsTrue()
    {
        XWikiDocument doc = mock(XWikiDocument.class);

        // A nested page reference: xwiki:Departments.Accounting.WebHome
        WikiReference wiki = new WikiReference("xwiki");
        SpaceReference depts = new SpaceReference("Departments", wiki);
        SpaceReference acct = new SpaceReference("Accounting", depts);
        DocumentReference ref = new DocumentReference("WebHome", acct);

        when(this.reader.getReferences(any(), any())).thenReturn(List.of(ref));

        // Serializer returns a fixed string for any reference passed to it.
        // The extendLink walk calls serialize on doc ref + its ancestors.
        when(this.linkSerializer.serialize(any(EntityReference.class))).thenAnswer(inv -> {
            EntityReference r = inv.getArgument(0);
            if (r instanceof DocumentReference) {
                return "Departments.Accounting.WebHome";
            } else if (r instanceof SpaceReference && "Accounting".equals(r.getName())) {
                return "Departments.Accounting";
            } else if (r instanceof SpaceReference && "Departments".equals(r.getName())) {
                return "Departments";
            } else {
                return r.getName();
            }
        });

        SolrInputDocument solrDoc = new SolrInputDocument();
        boolean modified = this.extractor.extract(doc, solrDoc);

        assertTrue(modified);

        Collection<Object> links = solrDoc.getFieldValues("links");
        assertTrue(links.contains("Departments.Accounting.WebHome"),
            "LINKS must contain the serialized document reference");
        assertEquals(1, links.size(),
            "LINKS must contain exactly the direct reference, not the extended ancestors");

        Collection<Object> linksExtended = solrDoc.getFieldValues("links_extended");
        // extendLink walks: doc ref itself + SpaceRef "Accounting" + SpaceRef "Departments" + WikiRef "xwiki"
        assertTrue(linksExtended.contains("Departments.Accounting.WebHome"),
            "LINKS_EXTENDED must contain the document reference itself");
        assertTrue(linksExtended.contains("Departments.Accounting"),
            "LINKS_EXTENDED must contain the space ancestor 'Departments.Accounting'");
        assertTrue(linksExtended.contains("Departments"),
            "LINKS_EXTENDED must contain the root space ancestor 'Departments'");
    }

    @Test
    void returnsFalseWhenNoReferences()
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        when(this.reader.getReferences(any(), any())).thenReturn(List.of());

        SolrInputDocument solrDoc = new SolrInputDocument();
        boolean modified = this.extractor.extract(doc, solrDoc);

        assertFalse(modified);
        assertNull(solrDoc.getFieldValues("links"), "No LINKS field must be added");
        assertNull(solrDoc.getFieldValues("links_extended"), "No LINKS_EXTENDED field must be added");
    }

    /**
     * Tripwire for the hardcoded Solr field names. {@link PagePropertyLinkExtractor} deliberately does
     * not bind to the internal {@code FieldUtils} class at runtime; it writes the literal field names
     * {@code "links"} / {@code "links_extended"} instead. If a future XWiki release ever renames these
     * schema fields, this assertion fails the moment we build against that version — turning what would
     * otherwise be a silent runtime breakage (the extractor writing to a field the backlink query no
     * longer reads) into a loud build-time signal to update the constants in the extractor.
     */
    @Test
    void hardcodedFieldNamesMatchCoreSolrSchema()
    {
        assertEquals(FieldUtils.LINKS, "links",
            "Core Solr 'links' field renamed — update SOLR_FIELD_LINKS in PagePropertyLinkExtractor");
        assertEquals(FieldUtils.LINKS_EXTENDED, "links_extended",
            "Core Solr 'links_extended' field renamed — update SOLR_FIELD_LINKS_EXTENDED in PagePropertyLinkExtractor");
    }
}
