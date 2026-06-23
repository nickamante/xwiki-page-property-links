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

import java.util.List;
import javax.inject.Named;
import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ComponentTest
class DefaultPagePropertyLinksConfigurationTest
{
    @InjectMockComponents
    private DefaultPagePropertyLinksConfiguration configuration;

    @MockComponent
    @Named("xwikiproperties")
    private ConfigurationSource configurationSource;

    @Test
    void nonRelationalDefaultsOff()
    {
        when(this.configurationSource.getProperty("pagePropertyLinks.supportNonRelationalStorage", Boolean.FALSE))
            .thenReturn(Boolean.FALSE);
        assertFalse(this.configuration.isNonRelationalStorageSupported());
    }

    @Test
    void nonRelationalCanBeEnabled()
    {
        when(this.configurationSource.getProperty("pagePropertyLinks.supportNonRelationalStorage", Boolean.FALSE))
            .thenReturn(Boolean.TRUE);
        assertTrue(this.configuration.isNonRelationalStorageSupported());
    }

    @Test
    void typeSetDefaultsToPage()
    {
        when(this.configurationSource.getProperty("pagePropertyLinks.referenceTypes", List.of("Page")))
            .thenReturn(List.of("Page"));
        assertEquals(List.of("Page"), this.configuration.getReferenceTypeSet());
    }
}
