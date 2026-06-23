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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.pagepropertylinks.PagePropertyLinksConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.refactoring.job.MoveRequest;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.StringListProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * On {@link DocumentRenamedEvent}, rewrites every {@code Page}-type object property across the wiki
 * that still holds the old reference to the new reference. Keeps stored values valid after a rename;
 * never touches the Solr index directly (that is the concern of {@link PagePropertyLinkExtractor}).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Serialize old/new {@link DocumentReference}s to local form (e.g.
 *       {@code Departments.Accounting.WebHome}).</li>
 *   <li>Find candidate documents via two HQL queries: one for {@code StringProperty} (single-value)
 *       and one for {@code DBStringListProperty} (relational multi-value). Optionally a third for
 *       non-relational {@code StringListProperty} when the flag is on.</li>
 *   <li>For each candidate, walk all objects and their properties; if the property class type is in
 *       the configured gate set (default {@code ["Page"]}) and the stored value equals the old
 *       reference (exact match — not prefix/substring), rewrite it to the new reference.</li>
 *   <li>Save once per changed document. Per-doc failures are caught and logged without aborting
 *       the rest.</li>
 * </ol>
 *
 * <p>The non-relational multi-value gate ({@link StringListProperty} that is NOT a
 * {@link DBStringListProperty}) matches the gate in {@link DefaultPagePropertyReferenceReader}
 * exactly, controlled by {@link PagePropertyLinksConfiguration#isNonRelationalStorageSupported()}.
 */
@Component
@Named("pagePropertyLinks.referenceUpdater")
@Singleton
public class PagePropertyReferenceUpdater extends AbstractEventListener
{
    /**
     * HQL: find documents with a single-value {@code StringProperty} whose value exactly equals
     * the old reference. {@code distinct} prevents duplicates when multiple objects in one doc match.
     */
    private static final String Q_STRING =
        "select distinct doc.fullName from XWikiDocument doc, BaseObject obj, StringProperty prop "
            + "where doc.fullName = obj.name and obj.id = prop.id.id and prop.value = :value";

    /**
     * HQL: find documents with a relational multi-value {@code DBStringListProperty} whose list
     * contains the old reference. {@code :value in elements(prop.list)} is the Hibernate collection
     * member-of syntax.
     *
     * <p>NOTE: Verify the join column ({@code obj.id = prop.id.id}) and the collection expression
     * against the XWiki 18.4.1 Hibernate mapping during in-wiki verification (Task 6 runbook).
     * Unit tests mock the QueryManager so HQL correctness is validated in-wiki, not here.
     */
    private static final String Q_DBLIST =
        "select distinct doc.fullName from XWikiDocument doc, BaseObject obj, DBStringListProperty prop "
            + "where doc.fullName = obj.name and obj.id = prop.id.id and :value in elements(prop.list)";

    /**
     * HQL: find documents with a non-relational multi-value {@code StringListProperty} that
     * contains the old reference as a substring of the stored text value. This is a broader match
     * (LIKE) — the exact-equals gate in {@link #rewriteProperty} handles false positives.
     *
     * <p>NOTE: The field name {@code textValue} is unverified against the 18.4.1 Hibernate mapping.
     * Confirm during in-wiki testing (Task 6 runbook). Unit tests mock the QueryManager.
     */
    private static final String Q_STRINGLIST =
        "select distinct doc.fullName from XWikiDocument doc, BaseObject obj, StringListProperty prop "
            + "where doc.fullName = obj.name and obj.id = prop.id.id and prop.textValue like :pattern";

    @Inject
    private Logger logger;

    @Inject
    private PagePropertyLinksConfiguration configuration;

    @Inject
    private QueryManager queryManager;

    /**
     * Serializes {@link DocumentReference}s to the {@code local} form stored by {@code Page}
     * properties (e.g. {@code Departments.Accounting.WebHome}, no wiki prefix).
     */
    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    /**
     * Resolves full document names (e.g. {@code Referrers.Entry1}) from HQL results to
     * {@link DocumentReference}s. {@code currentmixed} resolves relative to the current wiki.
     */
    @Inject
    @Named("currentmixed")
    private DocumentReferenceResolver<String> resolver;

    @Inject
    private Provider<XWikiContext> contextProvider;

    public PagePropertyReferenceUpdater()
    {
        super("pagePropertyLinks.referenceUpdater", new DocumentRenamedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        processEvent(event, source, data);
    }

    /**
     * Package-private entry point used directly by unit tests (which call
     * {@code updater.processEvent(...)} rather than going through the event bus).
     */
    void processEvent(Event event, Object source, Object data)
    {
        // Respect the rename dialog's "Update links" toggle. For a rename, the event data is the
        // MoveRequest (per the DocumentRenamedEvent contract), and MoveRequest.isUpdateLinks()
        // defaults to true. If the user opted out of updating incoming links, do not rewrite
        // Page-property references either — they are links too and must behave consistently with
        // content links. When data is not a MoveRequest (e.g. a programmatic rename or a remote
        // event), fall through and rewrite, preserving the default behaviour.
        if (data instanceof MoveRequest && !((MoveRequest) data).isUpdateLinks()) {
            return;
        }

        DocumentRenamedEvent renamed = (DocumentRenamedEvent) event;
        String oldRef = this.localSerializer.serialize(renamed.getSourceReference());
        String newRef = this.localSerializer.serialize(renamed.getTargetReference());
        XWikiContext context = this.contextProvider.get();

        int scanned = 0;
        int changed = 0;
        int failed = 0;

        for (String fullName : findCandidates(oldRef)) {
            scanned++;
            try {
                if (rewriteDocument(fullName, oldRef, newRef, context)) {
                    changed++;
                }
            } catch (Exception e) {
                failed++;
                this.logger.error(
                    "Failed to update Page references in [{}] after rename of [{}] -> [{}]",
                    fullName, oldRef, newRef, e);
            }
        }

        this.logger.info(
            "Page reference update for rename [{}] -> [{}]: scanned={}, changed={}, failed={}",
            oldRef, newRef, scanned, changed, failed);
    }

    // -----------------------------------------------------------------------
    // Candidate discovery
    // -----------------------------------------------------------------------

    private Set<String> findCandidates(String oldRef)
    {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(runSafely(Q_STRING, "value", oldRef, oldRef));
        candidates.addAll(runSafely(Q_DBLIST, "value", oldRef, oldRef));
        if (this.configuration.isNonRelationalStorageSupported()) {
            candidates.addAll(runSafely(Q_STRINGLIST, "pattern", "%" + oldRef + "%", oldRef));
        }
        return candidates;
    }

    /**
     * Executes a single HQL candidate query, returning an empty list if the query fails.
     * Each query is attempted independently so a failure in one does not prevent the others
     * from running — otherwise a bad HQL expression could silently skip later queries and
     * leave referrers unrewritten.
     *
     * @param hql the HQL query string (one of Q_STRING, Q_DBLIST, Q_STRINGLIST)
     * @param paramName the named bind parameter in the HQL
     * @param paramValue the value to bind
     * @param oldRef the original reference being renamed, used only for error logging
     * @return the list of full document names returned by the query, or an empty list on failure
     */
    private List<String> runSafely(String hql, String paramName, String paramValue, String oldRef)
    {
        try {
            return this.<String>runQuery(hql, paramName, paramValue);
        } catch (Exception e) {
            this.logger.error("Candidate query failed for rename of [{}]: [{}]", oldRef, hql, e);
            return List.of();
        }
    }

    private <T> List<T> runQuery(String hql, String paramName, String paramValue) throws Exception
    {
        Query query = this.queryManager.createQuery(hql, Query.HQL);
        query.bindValue(paramName, paramValue);
        return query.execute();
    }

    // -----------------------------------------------------------------------
    // Document rewriting
    // -----------------------------------------------------------------------

    private boolean rewriteDocument(String fullName, String oldRef, String newRef,
        XWikiContext context) throws Exception
    {
        XWiki wiki = context.getWiki();
        DocumentReference docRef = this.resolver.resolve(fullName);
        // Clone so we don't mutate the cached version before deciding to save.
        XWikiDocument document = wiki.getDocument(docRef, context).clone();

        List<String> gatedTypes = this.configuration.getReferenceTypeSet();
        boolean nonRelational = this.configuration.isNonRelationalStorageSupported();
        boolean modified = false;

        for (List<BaseObject> objectsOfClass : document.getXObjects().values()) {
            if (objectsOfClass == null) {
                continue;
            }
            for (BaseObject object : objectsOfClass) {
                if (object == null) {
                    continue;
                }
                BaseClass xclass = object.getXClass(context);
                if (xclass == null) {
                    continue;
                }
                for (Object fieldObj : xclass.getProperties()) {
                    PropertyClass propertyClass = (PropertyClass) fieldObj;
                    if (!isGated(propertyClass, gatedTypes)) {
                        continue;
                    }
                    BaseProperty<?> property =
                        (BaseProperty<?>) object.safeget(propertyClass.getName());
                    // Gate non-relational multi-value (StringListProperty that is NOT
                    // DBStringListProperty) behind the flag — matching the reader's gate exactly.
                    if (property == null
                        || (property instanceof StringListProperty
                            && !(property instanceof DBStringListProperty)
                            && !nonRelational)) {
                        continue;
                    }
                    modified |= rewriteProperty(property, oldRef, newRef);
                }
            }
        }

        if (modified) {
            wiki.saveDocument(document,
                "Updated Page reference after rename of [" + oldRef + "] to [" + newRef + "]",
                context);
        }
        return modified;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean rewriteProperty(BaseProperty<?> property, String oldRef, String newRef)
    {
        Object value = property.getValue();
        if (value instanceof List) {
            List<String> list = new ArrayList<>((List<String>) value);
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                if (oldRef.equals(list.get(i))) {   // exact match — no prefix/substring
                    list.set(i, newRef);
                    changed = true;
                }
            }
            if (changed) {
                // Use raw type to bypass the R extends EntityReference bound — setValue(Object)
                // is defined on BaseProperty directly and accepts any Object.
                ((BaseProperty) property).setValue(list);
            }
            return changed;
        } else if (oldRef.equals(value)) {           // exact match
            ((BaseProperty) property).setValue(newRef);
            return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean isGated(PropertyClass propertyClass, List<String> gatedTypes)
    {
        // getClassType() is deprecated in XWiki 18.4.1 but is the only public API available
        // for reading the class type string ("Page", "String", "DBList", …). There is no
        // getType() method on PropertyClass. Matches the gate in DefaultPagePropertyReferenceReader.
        String type = propertyClass.getClassType();
        return type != null && gatedTypes.contains(type);
    }
}
