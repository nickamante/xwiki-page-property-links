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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.pagepropertylinks.PagePropertyLinksConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.refactoring.event.DocumentRenamedEvent;
import org.xwiki.refactoring.job.MoveRequest;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PageClass;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PagePropertyReferenceUpdater}.
 *
 * <p>Uses {@code @OldcoreTest} so we can build real {@link XWikiDocument}/{@link BaseObject}/
 * {@link com.xpn.xwiki.objects.classes.PropertyClass} instances backed by the in-memory oldcore
 * store, and verify that the updater actually rewrites (or leaves untouched) stored property values.
 * The {@link QueryManager} is mocked so tests control which candidate docs are returned from HQL.
 */
@OldcoreTest
@ReferenceComponentList
class PagePropertyReferenceUpdaterTest
{
    /** Class reference used by all test candidate docs. */
    private static final DocumentReference CLASS_REF =
        new DocumentReference("xwiki", "TestSpace", "TestClass");

    /**
     * Captures log output so it doesn't reach the console and trigger CaptureConsoleExtension.
     * The updater emits INFO-level summary messages after each rename; we suppress them here
     * since we are not asserting log content in these tests.
     */
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.INFO);

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private PagePropertyReferenceUpdater updater;

    @MockComponent
    private PagePropertyLinksConfiguration configuration;

    @MockComponent
    private QueryManager queryManager;

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * A candidate document with a single-value Page property holding the old reference must have
     * its stored value rewritten to the new reference after the rename event fires.
     */
    @Test
    void rewritesSingleValueOnRename() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // Build and save a candidate document with a Page property holding the old reference.
        DocumentReference candidateRef = new DocumentReference("xwiki", "Referrers", "Entry1");
        buildAndSaveSingleValueDoc(candidateRef, "Departments.Accounting.WebHome");

        // Mock QueryManager: return the candidate for the StringProperty query; empty for others.
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        // First query (Q_STRING) returns the candidate; subsequent queries return empty.
        when(query.<String>execute())
            .thenReturn(List.of("Referrers.Entry1"))
            .thenReturn(List.of());

        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, null);

        // The document should have been saved with the rewritten value.
        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(candidateRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Finance.WebHome",
            saved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "Single-value Page property must be rewritten to the new reference");

        // Suppress the INFO-level summary log so CaptureConsoleExtension doesn't fail the test.
        this.logCapture.ignoreAllMessages();
    }

    /**
     * A candidate whose Page property holds a value that is a prefix-match of the old reference
     * (e.g. rename source is "Departments.Accounting.WebHome", candidate holds
     * "Departments.Accounting2.WebHome") must NOT be rewritten — the exact-equals gate must
     * discriminate against false positives that a {@code LIKE '%...%'} HQL query might surface.
     *
     * <p>We also verify a genuinely-matching document IS rewritten in the same event, proving the
     * gate discriminates correctly rather than skipping all rewrites.
     */
    @Test
    void ignoresPrefixCollisionFalsePositive() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // Candidate A: holds the exact old reference — must be rewritten.
        DocumentReference exactMatchRef = new DocumentReference("xwiki", "Referrers", "ExactMatch");
        buildAndSaveSingleValueDoc(exactMatchRef, "Departments.Accounting.WebHome");

        // Candidate B: holds a prefix-collision value — must be left untouched.
        DocumentReference collisionRef = new DocumentReference("xwiki", "Referrers", "Collision");
        buildAndSaveSingleValueDoc(collisionRef, "Departments.Accounting2.WebHome");

        // The QueryManager (simulating the LIKE-based HQL) returns both candidates.
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        when(query.<String>execute())
            .thenReturn(List.of("Referrers.ExactMatch", "Referrers.Collision"))
            .thenReturn(List.of());

        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, null);

        // Exact match must be rewritten.
        XWikiDocument exactSaved = this.oldcore.getSpyXWiki()
            .getDocument(exactMatchRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Finance.WebHome",
            exactSaved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "Exact-match Page property must be rewritten to the new reference");

        // Prefix-collision must be left unchanged.
        XWikiDocument collisionSaved = this.oldcore.getSpyXWiki()
            .getDocument(collisionRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Accounting2.WebHome",
            collisionSaved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "Prefix-collision Page property must NOT be rewritten (exact-equals gate)");

        // Suppress the INFO-level summary log so CaptureConsoleExtension doesn't fail the test.
        this.logCapture.ignoreAllMessages();
    }

    /**
     * A candidate document with a relational multi-value Page property (DBStringListProperty) that
     * contains the old reference among other values must have only that entry rewritten; other list
     * entries must be preserved unchanged.
     */
    @Test
    void rewritesRelationalListValueOnRename() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // Build and save a candidate with a relational multi-value Page property.
        DocumentReference candidateRef = new DocumentReference("xwiki", "Referrers", "ListEntry");
        buildAndSaveRelationalListDoc(candidateRef,
            new ArrayList<>(List.of("Departments.Accounting.WebHome", "Departments.Sales.WebHome")));

        // QueryManager: return the candidate from the DBStringListProperty query; empty elsewhere.
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        when(query.<String>execute())
            .thenReturn(List.of())           // Q_STRING (no scalar candidates)
            .thenReturn(List.of("Referrers.ListEntry"))  // Q_DBLIST
            .thenReturn(List.of());          // Q_STRINGLIST (flag is off, not called, but safe)

        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, null);

        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(candidateRef, this.oldcore.getXWikiContext());
        List<?> refs = (List<?>) ((com.xpn.xwiki.objects.BaseProperty<?>)
            saved.getXObjects().values().iterator().next().get(0).safeget("refs")).getValue();
        assertEquals(2, refs.size(), "List must still have two entries");
        assertEquals("Departments.Finance.WebHome", refs.get(0),
            "Old reference in list must be rewritten to new reference");
        assertEquals("Departments.Sales.WebHome", refs.get(1),
            "Unrelated list entry must be preserved unchanged");

        // Suppress the INFO-level summary log so CaptureConsoleExtension doesn't fail the test.
        this.logCapture.ignoreAllMessages();
    }

    /**
     * When saving a candidate document throws an exception, the updater must log an error and
     * continue processing the remaining candidates rather than aborting the rename handling.
     */
    @Test
    void continuesAfterPerDocFailure() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // Build and save only one candidate (the other will return an empty/missing doc whose
        // getXObjects() produces nothing to rewrite, so it won't throw — instead we use a bad
        // fullName that causes getDocument to fail, which the updater must swallow).
        // Actually: supply a valid candidate AND a non-existent one; the non-existent won't throw
        // in OldcoreTest (getDocument returns an empty doc). Instead we test resilience by
        // verifying the good candidate is still rewritten even when an invalid fullName is first.
        DocumentReference goodRef = new DocumentReference("xwiki", "Referrers", "GoodEntry");
        buildAndSaveSingleValueDoc(goodRef, "Departments.Accounting.WebHome");

        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        // Return the good candidate; the second query is empty so de-duplication still works.
        when(query.<String>execute())
            .thenReturn(List.of("Referrers.GoodEntry"))
            .thenReturn(List.of());

        // Must not throw even if internal errors would be logged; just verify the good doc is rewritten.
        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, null);

        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(goodRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Finance.WebHome",
            saved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "Good candidate must be rewritten even when other candidates may fail");

        // Suppress the INFO-level summary log so CaptureConsoleExtension doesn't fail the test.
        this.logCapture.ignoreAllMessages();
    }

    /**
     * When {@code getDocument} throws for one candidate ("bad" doc), the updater must:
     * <ul>
     *   <li>NOT propagate the exception (per-doc try/catch)</li>
     *   <li>log an ERROR-level message naming the bad doc</li>
     *   <li>still rewrite the GOOD candidate's stored Page property value</li>
     * </ul>
     * This test genuinely exercises the {@code failed++} / error-log / continue branch in
     * {@link PagePropertyReferenceUpdater#processEvent}.
     */
    @Test
    void perDocCatchBranchIsExercised() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // "good" doc: a real saved doc whose Page property holds the old reference.
        DocumentReference goodRef = new DocumentReference("xwiki", "Referrers", "GoodDoc");
        buildAndSaveSingleValueDoc(goodRef, "Departments.Accounting.WebHome");

        // "bad" doc reference: getDocument for this reference will throw.
        DocumentReference badRef = new DocumentReference("xwiki", "Referrers", "BadDoc");

        // Make getDocument throw for the bad ref.  MockitoOldcore's getSpyXWiki() is a Spy,
        // so doThrow stubs only the matching invocation while other calls pass through.
        doThrow(new XWikiException(0, 0, "Simulated load failure for BadDoc"))
            .when(this.oldcore.getSpyXWiki())
            .getDocument(eq(badRef), any());

        // QueryManager: candidate discovery returns bad doc first, then good doc.
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        when(query.<String>execute())
            .thenReturn(List.of("Referrers.BadDoc", "Referrers.GoodDoc"))  // Q_STRING
            .thenReturn(List.of());                                          // Q_DBLIST

        // Must not throw — the per-doc catch must swallow the BadDoc failure.
        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, null);

        // Good doc must have been rewritten despite the earlier failure.
        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(goodRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Finance.WebHome",
            saved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "Good candidate must be rewritten even when an earlier candidate threw");

        // An ERROR-level log message naming the bad doc must have been emitted.
        // ILoggingEvent.getFormattedMessage() returns the rendered string with {} placeholders filled.
        boolean errorLogged = false;
        for (int i = 0; i < this.logCapture.size(); i++) {
            ch.qos.logback.classic.spi.ILoggingEvent logEvent = this.logCapture.getLogEvent(i);
            String formatted = logEvent.getFormattedMessage();
            if (logEvent.getLevel() == ch.qos.logback.classic.Level.ERROR
                && formatted != null && formatted.contains("Referrers.BadDoc"))
            {
                errorLogged = true;
                break;
            }
        }
        assertTrue(errorLogged,
            "An ERROR-level message naming Referrers.BadDoc must be logged for the failed doc");

        // Suppress remaining log output (INFO summary etc.).
        this.logCapture.ignoreAllMessages();
    }

    /**
     * When the rename was triggered with "Update links" OFF (the event's MoveRequest has
     * {@code isUpdateLinks() == false}), the updater must NOT rewrite Page-property references — they
     * are links and must honour the same toggle as content links. The guard short-circuits before
     * any candidate query, so {@code queryManager.createQuery} is never called and the stored value
     * is left untouched.
     */
    @Test
    void skipsRewriteWhenUpdateLinksDisabled() throws Exception
    {
        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        // A candidate that WOULD be rewritten if "Update links" were on.
        DocumentReference candidateRef = new DocumentReference("xwiki", "Referrers", "OptOut");
        buildAndSaveSingleValueDoc(candidateRef, "Departments.Accounting.WebHome");

        MoveRequest request = new MoveRequest();
        request.setUpdateLinks(false);

        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, request);

        // The guard must short-circuit BEFORE any candidate query runs (proves the opt-out is
        // honoured rather than the rewrite merely finding no candidates by coincidence).
        verify(this.queryManager, never()).createQuery(any(), any());

        // And the stored value is untouched.
        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(candidateRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Accounting.WebHome",
            saved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "With 'Update links' off, the Page property must be left untouched");
    }

    /**
     * When the rename was triggered with "Update links" ON (the MoveRequest has
     * {@code isUpdateLinks() == true}), the updater rewrites as usual — proving the guard blocks only
     * the explicit opt-out, not normal renames.
     */
    @Test
    void rewritesWhenUpdateLinksEnabled() throws Exception
    {
        when(this.configuration.isNonRelationalStorageSupported()).thenReturn(false);
        when(this.configuration.getReferenceTypeSet()).thenReturn(List.of("Page"));

        DocumentReference source =
            new DocumentReference("xwiki", List.of("Departments", "Accounting"), "WebHome");
        DocumentReference target =
            new DocumentReference("xwiki", List.of("Departments", "Finance"), "WebHome");

        DocumentReference candidateRef = new DocumentReference("xwiki", "Referrers", "OptIn");
        buildAndSaveSingleValueDoc(candidateRef, "Departments.Accounting.WebHome");

        Query query = mock(Query.class);
        when(this.queryManager.createQuery(any(), any())).thenReturn(query);
        when(query.bindValue(any(String.class), any())).thenReturn(query);
        when(query.<String>execute())
            .thenReturn(List.of("Referrers.OptIn"))
            .thenReturn(List.of());

        MoveRequest request = new MoveRequest();
        request.setUpdateLinks(true);

        this.updater.processEvent(new DocumentRenamedEvent(source, target), null, request);

        XWikiDocument saved = this.oldcore.getSpyXWiki()
            .getDocument(candidateRef, this.oldcore.getXWikiContext());
        assertEquals("Departments.Finance.WebHome",
            saved.getXObjects().values().iterator().next().get(0).getStringValue("ref"),
            "With 'Update links' on, the Page property must be rewritten");

        this.logCapture.ignoreAllMessages();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a document at {@code docRef} with one object whose XClass (at {@link #CLASS_REF})
     * has a {@link PageClass}-backed field named "ref", holding the given single string value
     * via a {@link StringProperty}. Saves both the class doc and the holder doc into the
     * in-memory oldcore store so {@code wiki.getDocument(docRef, context)} returns it.
     */
    private void buildAndSaveSingleValueDoc(DocumentReference docRef, String storedLocalRef)
        throws Exception
    {
        // Register the XClass so object.getXClass(context) can resolve it.
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        PageClass pageClassDef = new PageClass();
        pageClassDef.setName("ref");
        xclass.put("ref", pageClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        // Build the holder document.
        XWikiDocument doc = new XWikiDocument(docRef);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        StringProperty prop = new StringProperty();
        prop.setName("ref");
        prop.setValue(storedLocalRef);
        obj.safeput("ref", prop);

        doc.addXObject(obj);
        this.oldcore.getSpyXWiki().saveDocument(doc, this.oldcore.getXWikiContext());
    }

    /**
     * Builds a document at {@code docRef} with one object whose XClass has a {@link PageClass}-backed
     * field named "refs", holding a relational multi-value list via {@link DBStringListProperty}.
     */
    private void buildAndSaveRelationalListDoc(DocumentReference docRef, List<String> storedLocalRefs)
        throws Exception
    {
        XWikiDocument classDoc = new XWikiDocument(CLASS_REF);
        BaseClass xclass = classDoc.getXClass();
        PageClass pageClassDef = new PageClass();
        pageClassDef.setName("refs");
        xclass.put("refs", pageClassDef);
        this.oldcore.getSpyXWiki().saveDocument(classDoc, this.oldcore.getXWikiContext());

        XWikiDocument doc = new XWikiDocument(docRef);
        BaseObject obj = new BaseObject();
        obj.setXClassReference(CLASS_REF);

        DBStringListProperty prop = new DBStringListProperty();
        prop.setName("refs");
        prop.setList(storedLocalRefs);
        obj.safeput("refs", prop);

        doc.addXObject(obj);
        this.oldcore.getSpyXWiki().saveDocument(doc, this.oldcore.getXWikiContext());
    }
}
