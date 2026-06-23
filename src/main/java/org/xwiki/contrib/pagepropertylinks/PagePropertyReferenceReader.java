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

package org.xwiki.contrib.pagepropertylinks;

import java.util.List;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;

import org.xwiki.component.annotation.Role;
import org.xwiki.model.reference.DocumentReference;

/**
 * Yields the Page-type object-property references held by a document. Single source of truth for
 * "which references exist", applying the type gate and the non-relational storage flag.
 */
@Role
public interface PagePropertyReferenceReader
{
    /**
     * @param document the document whose object properties are read
     * @param context the current XWiki context; must be non-null (it is dereferenced via
     *     {@code object.getXClass(context)})
     * @return all DocumentReferences found in Page-typed (or configured-type) object properties
     */
    List<DocumentReference> getReferences(XWikiDocument document, XWikiContext context);
}
