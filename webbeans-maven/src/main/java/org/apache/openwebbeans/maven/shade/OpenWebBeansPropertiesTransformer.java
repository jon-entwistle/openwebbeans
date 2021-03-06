/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openwebbeans.maven.shade;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class OpenWebBeansPropertiesTransformer implements ResourceTransformer
{
    private final List<Properties> configurations = new ArrayList<Properties>();

    private String resource = "META-INF/openwebbeans/openwebbeans.properties";
    private String ordinalKey = "configuration.ordinal";
    private int defaultOrdinal = 100;
    private boolean reverseOrder = false;

    @Override
    public boolean canTransformResource(final String s)
    {
        return resource.equals(s);
    }

    @Override
    public void processResource(final String s, final InputStream inputStream, final List<Relocator> list) throws IOException
    {
        final Properties p = new Properties();
        p.load(inputStream);
        configurations.add(p);
    }

    @Override
    public boolean hasTransformedResource()
    {
        return !configurations.isEmpty();
    }

    @Override
    public void modifyOutputStream(final JarOutputStream jarOutputStream) throws IOException
    {
        final Properties out = mergeProperties(sortProperties(configurations));
        jarOutputStream.putNextEntry(new ZipEntry(resource));
        out.store(jarOutputStream, "# maven " + resource + " merge");
        jarOutputStream.closeEntry();
    }

    public void setReverseOrder(final boolean reverseOrder)
    {
        this.reverseOrder = reverseOrder;
    }

    public void setResource(final String resource)
    {
        this.resource = resource;
    }

    public void setOrdinalKey(final String ordinalKey)
    {
        this.ordinalKey = ordinalKey;
    }

    public void setDefaultOrdinal(final int defaultOrdinal)
    {
        this.defaultOrdinal = defaultOrdinal;
    }

    private List<Properties> sortProperties(List<Properties> allProperties)
    {
        final List<Properties> sortedProperties = new ArrayList<Properties>();
        for (final Properties p : allProperties)
        {
            final int configOrder = getConfigurationOrdinal(p);

            int i;
            for (i = 0; i < sortedProperties.size(); i++)
            {
                final int listConfigOrder = getConfigurationOrdinal(sortedProperties.get(i));
                if ((!reverseOrder && listConfigOrder > configOrder) || (reverseOrder && listConfigOrder < configOrder))
                {
                    break;
                }
            }
            sortedProperties.add(i, p);
        }
        return sortedProperties;
    }

    private int getConfigurationOrdinal(final Properties p)
    {
        final String configOrderString = p.getProperty(ordinalKey);
        if (configOrderString != null && configOrderString.length() > 0)
        {
            return Integer.parseInt(configOrderString);
        }
        return defaultOrdinal;
    }

    private static Properties mergeProperties(final List<Properties> sortedProperties)
    {
        final Properties mergedProperties = new Properties();
        for (final Properties p : sortedProperties)
        {
            mergedProperties.putAll(p);
        }

        return mergedProperties;
    }
}
