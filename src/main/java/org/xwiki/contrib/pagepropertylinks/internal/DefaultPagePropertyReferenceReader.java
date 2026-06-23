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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.StringListProperty;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.pagepropertylinks.PagePropertyLinksConfiguration;
import org.xwiki.contrib.pagepropertylinks.PagePropertyReferenceReader;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;

/**
 * Default reader. Gates properties by class type (default {@code "Page"}) and reads scalar/list
 * values. {@link StringListProperty}-backed (non-relational multi-value) values are included only
 * when the non-relational storage flag is on.
 *
 * <p>The stored "local" reference string (e.g. {@code Departments.Accounting.WebHome}) is
 * resolved via the {@code currentmixed} {@link DocumentReferenceResolver}, which handles local
 * references relative to the current wiki — matching how {@code Page} property values are stored.
 */
@Component
@Singleton
public class DefaultPagePropertyReferenceReader implements PagePropertyReferenceReader
{
    @Inject
    private PagePropertyLinksConfiguration configuration;

    /**
     * Resolves stored "local" reference strings to {@link DocumentReference}s.
     * The {@code currentmixed} hint resolves unqualified strings relative to the current wiki,
     * which matches the storage format used by XWiki {@code Page}-type properties.
     */
    @Inject
    @Named("currentmixed")
    private DocumentReferenceResolver<String> resolver;

    @Override
    public List<DocumentReference> getReferences(XWikiDocument document, XWikiContext context)
    {
        List<DocumentReference> references = new ArrayList<>();
        List<String> gatedTypes = this.configuration.getReferenceTypeSet();
        boolean nonRelational = this.configuration.isNonRelationalStorageSupported();

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
                    BaseProperty<?> property = (BaseProperty<?>) object.safeget(propertyClass.getName());
                    if (property == null) {
                        continue;
                    }
                    // Non-relational multi-value Page property == StringListProperty (a ListProperty
                    // whose getValue() returns the parsed list). Relational multi-value is
                    // DBStringListProperty (a sibling — extends ListProperty, not StringListProperty).
                    // Gate ONLY the non-relational list behind the flag.
                    if (property instanceof StringListProperty
                        && !(property instanceof DBStringListProperty)
                        && !nonRelational) {
                        continue;
                    }
                    addValues(property, references, document.getDocumentReference());
                }
            }
        }
        return references;
    }

    @SuppressWarnings("deprecation")
    private boolean isGated(PropertyClass propertyClass, List<String> gatedTypes)
    {
        // getClassType() is deprecated in 18.4.1 but is the only public API available to read
        // the class type string (e.g. "Page", "String", "DBList"). The private getTypeName()
        // cannot be called from outside the class.
        String type = propertyClass.getClassType();
        return type != null && gatedTypes.contains(type);
    }

    private void addValues(BaseProperty<?> property, List<DocumentReference> references,
        DocumentReference baseReference)
    {
        Object value = property.getValue();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null) {
                    addValue(String.valueOf(item), references, baseReference);
                }
            }
        } else if (value != null) {
            addValue(String.valueOf(value), references, baseReference);
        }
    }

    private void addValue(String stored, List<DocumentReference> references,
        DocumentReference baseReference)
    {
        if (stored != null && !stored.isEmpty()) {
            references.add(this.resolver.resolve(stored, baseReference));
        }
    }
}
