/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.plugins.shade.it.spi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;

public class CustomTransformer implements ResourceTransformer {
    @Override
    public boolean canTransformResource( final String s ) {
        return false;
    }

    @Override
    public void processResource( final String s, final InputStream inputStream,
                                 final List<Relocator> list ) throws IOException {
        // no-op
    }

    @Override
    public boolean hasTransformedResource() {
        return true;
    }

    @Override
    public void modifyOutputStream( final JarOutputStream jarOutputStream ) throws IOException {
        jarOutputStream.putNextEntry( new JarEntry( CustomTransformer.class.getName() ) );
        jarOutputStream.write( "executed".getBytes( StandardCharsets.UTF_8 ) );
        jarOutputStream.closeEntry();
    }
}
