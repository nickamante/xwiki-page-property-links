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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.solr.common.SolrInputDocument;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.pagepropertylinks.PagePropertyReferenceReader;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.search.solr.SolrEntityMetadataExtractor;
import org.xwiki.search.solr.internal.metadata.SolrLinkSerializer;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Adds a document's Page-type object-property references to the Solr {@code links} and
 * {@code links_extended} fields, mirroring
 * {@code DocumentSolrMetadataExtractor.setLinks}/{@code extendLink} so they resolve as
 * native backlinks. No field is saved to the document; no mirror object is written — the
 * Solr index is the only target.
 *
 * <p>Registered as {@code "pagePropertyLinks"} to satisfy the unique role-hint requirement
 * of {@link SolrEntityMetadataExtractor}.
 */
@Component
@Named("pagePropertyLinks")
@Singleton
public class PagePropertyLinkExtractor implements SolrEntityMetadataExtractor<XWikiDocument>
{
    /**
     * Solr field holding a document's direct links. Mirrors the core schema field name
     * (the {@code LINKS} constant of {@code org.xwiki.search.solr.internal.api.FieldUtils}),
     * hardcoded here so the extension does not bind to that internal class.
     */
    private static final String SOLR_FIELD_LINKS = "links";

    /**
     * Solr field holding a document's links plus all their ancestors. Mirrors the core schema
     * field name ({@code FieldUtils.LINKS_EXTENDED}); hardcoded for the same reason.
     */
    private static final String SOLR_FIELD_LINKS_EXTENDED = "links_extended";

    @Inject
    private PagePropertyReferenceReader reader;

    @Inject
    private SolrLinkSerializer linkSerializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    /**
     * Reads the Page-type object-property references from the document and appends them to
     * the Solr document's {@code links} and {@code links_extended} fields.
     *
     * @param entity the XWiki document being indexed
     * @param solrDocument the Solr input document to append fields to
     * @return {@code true} iff at least one field was added (i.e., the document had references)
     */
    @Override
    public boolean extract(XWikiDocument entity, SolrInputDocument solrDocument)
    {
        XWikiContext context = this.contextProvider.get();
        List<DocumentReference> references = this.reader.getReferences(entity, context);
        if (references.isEmpty()) {
            return false;
        }

        Set<String> links = new HashSet<>(references.size());
        // ×4 heuristic: each reference contributes itself + ~3 ancestor entries on average
        Set<String> linksExtended = new HashSet<>(references.size() * 4);

        for (DocumentReference reference : references) {
            String serialized = this.linkSerializer.serialize(reference);
            links.add(serialized);
            linksExtended.add(serialized);
            extendLink(reference, linksExtended);
        }

        for (String link : links) {
            solrDocument.addField(SOLR_FIELD_LINKS, link);
        }
        for (String extended : linksExtended) {
            solrDocument.addField(SOLR_FIELD_LINKS_EXTENDED, extended);
        }
        return true;
    }

    /**
     * Verbatim port of {@code AbstractSolrMetadataExtractor.extendLink} from XWiki 18.4.1.
     * Strips parameters from the reference, then walks up the ancestor chain serializing
     * each ancestor into the extended-links set.
     */
    private void extendLink(EntityReference reference, Set<String> linksExtended)
    {
        for (EntityReference parent = reference.getParameters().isEmpty() ? reference
            : new EntityReference(reference.getName(), reference.getType(), reference.getParent(), null);
            parent != null; parent = parent.getParent()) {
            linksExtended.add(this.linkSerializer.serialize(parent));
        }
    }
}
