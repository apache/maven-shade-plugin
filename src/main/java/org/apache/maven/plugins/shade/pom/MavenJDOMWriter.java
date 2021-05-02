package org.apache.maven.plugins.shade.pom;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.ActivationFile;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.CiManagement;
import org.apache.maven.model.ConfigurationContainer;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.DeploymentRepository;
import org.apache.maven.model.Developer;
import org.apache.maven.model.DistributionManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Extension;
import org.apache.maven.model.FileSet;
import org.apache.maven.model.IssueManagement;
import org.apache.maven.model.License;
import org.apache.maven.model.MailingList;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.Notifier;
import org.apache.maven.model.Organization;
import org.apache.maven.model.Parent;
import org.apache.maven.model.PatternSet;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginConfiguration;
import org.apache.maven.model.PluginContainer;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Prerequisites;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Relocation;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.ReportSet;
import org.apache.maven.model.Reporting;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryBase;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.model.Resource;
import org.apache.maven.model.Scm;
import org.apache.maven.model.Site;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jdom2.Content;
import org.jdom2.DefaultJDOMFactory;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Text;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

/**
 * Class MavenJDOMWriter.
 *
 */
public class MavenJDOMWriter
{
    /**
     * Field factory
     */
    private DefaultJDOMFactory factory;

    /**
     * Field lineSeparator
     */
    private String lineSeparator;

    public MavenJDOMWriter()
    {
        factory = new DefaultJDOMFactory();
        lineSeparator = "\n";
    }

    /**
     * Method findAndReplaceProperties
     *
     * @param counter {@link Counter}
     * @param props {@link Map}
     * @param name The name.
     * @param parent {@link Element}
     * @return {@link Element}
     */
    protected Element findAndReplaceProperties( Counter counter, Element parent, String name, Map props )
    {
        Map<String, String> properties = props;
        boolean shouldExist = properties != null && !properties.isEmpty();
        Element element = updateElement( counter, parent, name, shouldExist );
        if ( shouldExist )
        {
            Counter innerCounter = new Counter( counter.getDepth() + 1 );
            for ( Map.Entry<String, String> entry : properties.entrySet() )
            {
                String key = entry.getKey();
                findAndReplaceSimpleElement( innerCounter, element, key, entry.getValue(), null );
            }
            List<String> lst = new ArrayList<>( properties.keySet() );
            Iterator<Element> it = element.getChildren().iterator();
            while ( it.hasNext() )
            {
                Element elem = it.next();
                String key = elem.getName();
                if ( !lst.contains( key ) )
                {
                    it.remove();
                }
            }
        }
        return element;
    }

    /**
     * Method findAndReplaceSimpleElement
     *
     * @param counter {@link Counter}
     * @param defaultValue The default value.
     * @param text The text.
     * @param name The name.
     * @param parent The parent.
     * @return {@link Element}
     */
    protected Element findAndReplaceSimpleElement( Counter counter, Element parent, String name, String text,
                                                   String defaultValue )
    {
        if ( defaultValue != null && text != null && defaultValue.equals( text ) )
        {
            Element element = parent.getChild( name, parent.getNamespace() );
            // if exist and is default value or if doesn't exist.. just keep the way it is..
            if ( element == null || defaultValue.equals( element.getText() ) )
            {
                return element;
            }
        }
        boolean shouldExist = text != null && text.trim().length() > 0;
        Element element = updateElement( counter, parent, name, shouldExist );
        if ( shouldExist )
        {
            element.setText( text );
        }
        return element;
    }

    /**
     * Method findAndReplaceSimpleLists
     *
     * @param counter {@link Counter}
     * @param childName The childName
     * @param parentName The parentName
     * @param list The list of elements.
     * @param parent The parent.
     * @return {@link Element}
     */
    protected Element findAndReplaceSimpleLists( Counter counter, Element parent, Collection<String> list,
                                                 String parentName, String childName )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentName, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childName, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( String value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childName, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                el.setText( value );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
        return element;
    }

    /**
     * Method findAndReplaceXpp3DOM
     *
     * @param counter {@link Counter}
     * @param dom {@link Xpp3Dom}
     * @param name The name.
     * @param parent The parent.
     * @return {@link Element}
     */
    protected Element findAndReplaceXpp3DOM( Counter counter, Element parent, String name, Xpp3Dom dom )
    {
        boolean shouldExist = dom != null && ( dom.getChildCount() > 0 || dom.getValue() != null );
        Element element = updateElement( counter, parent, name, shouldExist );
        if ( shouldExist )
        {
            replaceXpp3DOM( element, dom, new Counter( counter.getDepth() + 1 ) );
        }
        return element;
    }

    /**
     * Method insertAtPreferredLocation
     *
     * @param parent The parent.
     * @param counter {@link Counter}
     * @param child {@link Element}
     */
    protected void insertAtPreferredLocation( Element parent, Element child, Counter counter )
    {
        int contentIndex = 0;
        int elementCounter = 0;
        Iterator<Content> it = parent.getContent().iterator();
        Text lastText = null;
        int offset = 0;
        while ( it.hasNext() && elementCounter <= counter.getCurrentIndex() )
        {
            Object next = it.next();
            offset = offset + 1;
            if ( next instanceof Element )
            {
                elementCounter = elementCounter + 1;
                contentIndex = contentIndex + offset;
                offset = 0;
            }
            if ( next instanceof Text && it.hasNext() )
            {
                lastText = (Text) next;
            }
        }
        if ( lastText != null && lastText.getTextTrim().length() == 0 )
        {
            lastText = lastText.clone();
        }
        else
        {
            StringBuilder starter = new StringBuilder( lineSeparator );
            for ( int i = 0; i < counter.getDepth(); i++ )
            {
                starter.append( "    " ); // TODO make settable?
            }
            lastText = factory.text( starter.toString() );
        }
        if ( parent.getContentSize() == 0 )
        {
            Text finalText = lastText.clone();
            finalText.setText( finalText.getText().substring( 0, finalText.getText().length() - "    ".length() ) );
            parent.addContent( contentIndex, finalText );
        }
        parent.addContent( contentIndex, child );
        parent.addContent( contentIndex, lastText );
    }

    /**
     * Method iterateContributor
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateContributor( Counter counter, Element parent, Collection<Contributor> list,
                                       String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Contributor value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateContributor( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateDependency
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateDependency( Counter counter, Element parent, Collection<Dependency> list,
                                      String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Dependency value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateDependency( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateDeveloper
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateDeveloper( Counter counter, Element parent, Collection<Developer> list,
                                     String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Developer value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateDeveloper( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateExclusion
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateExclusion( Counter counter, Element parent, Collection<Exclusion> list,
                                     String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Exclusion value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateExclusion( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateExtension
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateExtension( Counter counter, Element parent, Collection<Extension> list,
                                     String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Extension value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateExtension( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateLicense
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateLicense( Counter counter, Element parent, Collection<License> list,
                                   String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( License value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateLicense( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateMailingList
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateMailingList( Counter counter, Element parent, Collection<MailingList> list,
                                       String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( MailingList value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateMailingList( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateNotifier
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateNotifier( Counter counter, Element parent, Collection<Notifier> list,
                                    String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Notifier value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateNotifier( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iteratePlugin
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iteratePlugin( Counter counter, Element parent, Collection<Plugin> list,
                                  String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Plugin value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updatePlugin( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iteratePluginExecution
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iteratePluginExecution( Counter counter, Element parent, Collection<PluginExecution> list,
                                           String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( PluginExecution value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updatePluginExecution( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateProfile
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateProfile( Counter counter, Element parent, Collection<Profile> list,
                                   String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Profile value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateProfile( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateReportPlugin
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateReportPlugin( Counter counter, Element parent, Collection<ReportPlugin> list,
                                        String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( ReportPlugin value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateReportPlugin( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateReportSet
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateReportSet( Counter counter, Element parent, Collection<ReportSet> list,
                                     String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( ReportSet value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateReportSet( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateRepository
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateRepository( Counter counter, Element parent, Collection<Repository> list,
                                      String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Repository value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateRepository( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method iterateResource
     *
     * @param counter {@link Counter}
     * @param childTag The childTag
     * @param parentTag The parentTag
     * @param list The list of elements.
     * @param parent The parent.
     */
    protected void iterateResource( Counter counter, Element parent, Collection<Resource> list,
                                    String parentTag, String childTag )
    {
        boolean shouldExist = list != null && list.size() > 0;
        Element element = updateElement( counter, parent, parentTag, shouldExist );
        if ( shouldExist )
        {
            Iterator<Element> elIt = element.getChildren( childTag, element.getNamespace() ).iterator();
            if ( !elIt.hasNext() )
            {
                elIt = null;
            }
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            for ( Resource value : list )
            {
                Element el;
                if ( elIt != null && elIt.hasNext() )
                {
                    el = elIt.next();
                    if ( !elIt.hasNext() )
                    {
                        elIt = null;
                    }
                }
                else
                {
                    el = factory.element( childTag, element.getNamespace() );
                    insertAtPreferredLocation( element, el, innerCount );
                }
                updateResource( value, childTag, innerCount, el );
                innerCount.increaseCount();
            }
            if ( elIt != null )
            {
                while ( elIt.hasNext() )
                {
                    elIt.next();
                    elIt.remove();
                }
            }
        }
    }

    /**
     * Method replaceXpp3DOM
     *
     * @param parent The parent.
     * @param counter {@link Counter}
     * @param parentDom {@link Element}
     */
    protected void replaceXpp3DOM( Element parent, Xpp3Dom parentDom, Counter counter )
    {
        if ( parentDom.getChildCount() > 0 )
        {
            Xpp3Dom[] childs = parentDom.getChildren();
            Collection<Xpp3Dom> domChilds = new ArrayList<>();
            Collections.addAll( domChilds, childs );
            // int domIndex = 0;
            for ( Element elem : parent.getChildren() )
            {
                Xpp3Dom corrDom = null;
                for ( Xpp3Dom dm : domChilds )
                {
                    if ( dm.getName().equals( elem.getName() ) )
                    {
                        corrDom = dm;
                        break;
                    }
                }
                if ( corrDom != null )
                {
                    domChilds.remove( corrDom );
                    replaceXpp3DOM( elem, corrDom, new Counter( counter.getDepth() + 1 ) );
                    counter.increaseCount();
                }
                else
                {
                    parent.removeContent( elem );
                }
            }
            for ( Xpp3Dom dm : domChilds )
            {
                Element elem = factory.element( dm.getName(), parent.getNamespace() );
                insertAtPreferredLocation( parent, elem, counter );
                counter.increaseCount();
                replaceXpp3DOM( elem, dm, new Counter( counter.getDepth() + 1 ) );
            }
        }
        else if ( parentDom.getValue() != null )
        {
            parent.setText( parentDom.getValue() );
        }
    }

    /**
     * Method updateActivationFile
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateActivationFile( ActivationFile value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "missing", value.getMissing(), null );
            findAndReplaceSimpleElement( innerCount, root, "exists", value.getExists(), null );
        }
    }

    /**
     * Method updateActivationOS
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateActivationOS( ActivationOS value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "family", value.getFamily(), null );
            findAndReplaceSimpleElement( innerCount, root, "arch", value.getArch(), null );
            findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
        }
    }

    /**
     * Method updateActivationProperty
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateActivationProperty( ActivationProperty value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "value", value.getValue(), null );
        }
    }

    /**
     * Method updateBuild
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    //CHECKSTYLE_OFF: LineLength
    protected void updateBuild( Build value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "sourceDirectory", value.getSourceDirectory(), null );
            findAndReplaceSimpleElement( innerCount, root, "scriptSourceDirectory", value.getScriptSourceDirectory(),
                                         null );
            findAndReplaceSimpleElement( innerCount, root, "testSourceDirectory", value.getTestSourceDirectory(), null );
            findAndReplaceSimpleElement( innerCount, root, "outputDirectory", value.getOutputDirectory(), null );
            findAndReplaceSimpleElement( innerCount, root, "testOutputDirectory", value.getTestOutputDirectory(), null );
            iterateExtension( innerCount, root, value.getExtensions(), "extensions", "extension" );
            findAndReplaceSimpleElement( innerCount, root, "defaultGoal", value.getDefaultGoal(), null );
            iterateResource( innerCount, root, value.getResources(), "resources", "resource" );
            iterateResource( innerCount, root, value.getTestResources(), "testResources", "testResource" );
            findAndReplaceSimpleElement( innerCount, root, "directory", value.getDirectory(), null );
            findAndReplaceSimpleElement( innerCount, root, "finalName", value.getFinalName(), null );
            findAndReplaceSimpleLists( innerCount, root, value.getFilters(), "filters", "filter" );
            updatePluginManagement( value.getPluginManagement(), "pluginManagement", innerCount, root );
            iteratePlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }
    //CHECKSTYLE_ON: LineLength

    /**
     * Method updateBuildBase
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateBuildBase( BuildBase value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "defaultGoal", value.getDefaultGoal(), null );
            iterateResource( innerCount, root, value.getResources(), "resources", "resource" );
            iterateResource( innerCount, root, value.getTestResources(), "testResources", "testResource" );
            findAndReplaceSimpleElement( innerCount, root, "directory", value.getDirectory(), null );
            findAndReplaceSimpleElement( innerCount, root, "finalName", value.getFinalName(), null );
            findAndReplaceSimpleLists( innerCount, root, value.getFilters(), "filters", "filter" );
            updatePluginManagement( value.getPluginManagement(), "pluginManagement", innerCount, root );
            iteratePlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }

    /**
     * Method updateCiManagement
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateCiManagement( CiManagement value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "system", value.getSystem(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
            iterateNotifier( innerCount, root, value.getNotifiers(), "notifiers", "notifier" );
        }
    }

    /**
     * Method updateConfigurationContainer
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateConfigurationContainer( ConfigurationContainer value, String xmlTag, Counter counter,
                                                 Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "inherited", value.getInherited(), null );
            findAndReplaceXpp3DOM( innerCount, root, "configuration", (Xpp3Dom) value.getConfiguration() );
        }
    }

    /**
     * Method updateContributor
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateContributor( Contributor value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "email", value.getEmail(), null );
        findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        findAndReplaceSimpleElement( innerCount, root, "organization", value.getOrganization(), null );
        findAndReplaceSimpleElement( innerCount, root, "organizationUrl", value.getOrganizationUrl(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getRoles(), "roles", "role" );
        findAndReplaceSimpleElement( innerCount, root, "timezone", value.getTimezone(), null );
        findAndReplaceProperties( innerCount, root, "properties", value.getProperties() );
    }

    /**
     * Method updateDependency
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateDependency( Dependency value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
        findAndReplaceSimpleElement( innerCount, root, "type", value.getType(), "jar" );
        findAndReplaceSimpleElement( innerCount, root, "classifier", value.getClassifier(), null );
        findAndReplaceSimpleElement( innerCount, root, "scope", value.getScope(), null );
        findAndReplaceSimpleElement( innerCount, root, "systemPath", value.getSystemPath(), null );
        iterateExclusion( innerCount, root, value.getExclusions(), "exclusions", "exclusion" );
        findAndReplaceSimpleElement( innerCount, root, "optional",
                                     !value.isOptional() ? null : String.valueOf( value.isOptional() ), "false" );
    }

    /**
     * Method updateDependencyManagement
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateDependencyManagement( DependencyManagement value, String xmlTag, Counter counter,
                                               Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            iterateDependency( innerCount, root, value.getDependencies(), "dependencies", "dependency" );
        }
    }

    /**
     * Method updateDeploymentRepository
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateDeploymentRepository( DeploymentRepository value, String xmlTag, Counter counter,
                                               Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "uniqueVersion",
                                         value.isUniqueVersion() ? null : String.valueOf( value.isUniqueVersion() ),
                                         "true" );
            findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), null );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
            findAndReplaceSimpleElement( innerCount, root, "layout", value.getLayout(), "default" );
        }
    }

    /**
     * Method updateDeveloper
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateDeveloper( Developer value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), null );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "email", value.getEmail(), null );
        findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        findAndReplaceSimpleElement( innerCount, root, "organization", value.getOrganization(), null );
        findAndReplaceSimpleElement( innerCount, root, "organizationUrl", value.getOrganizationUrl(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getRoles(), "roles", "role" );
        findAndReplaceSimpleElement( innerCount, root, "timezone", value.getTimezone(), null );
        findAndReplaceProperties( innerCount, root, "properties", value.getProperties() );
    }

    /**
     * Method updateDistributionManagement
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateDistributionManagement( DistributionManagement value, String xmlTag, Counter counter,
                                                 Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            updateDeploymentRepository( value.getRepository(), "repository", innerCount, root );
            updateDeploymentRepository( value.getSnapshotRepository(), "snapshotRepository", innerCount, root );
            updateSite( value.getSite(), "site", innerCount, root );
            findAndReplaceSimpleElement( innerCount, root, "downloadUrl", value.getDownloadUrl(), null );
            updateRelocation( value.getRelocation(), "relocation", innerCount, root );
            findAndReplaceSimpleElement( innerCount, root, "status", value.getStatus(), null );
        }
    }

    /**
     * Method updateElement
     *
     * @param counter {@link Counter}
     * @param shouldExist should exist.
     * @param name The name.
     * @param parent The parent.
     * @return {@link Element}
     */
    protected Element updateElement( Counter counter, Element parent, String name, boolean shouldExist )
    {
        Element element = parent.getChild( name, parent.getNamespace() );
        if ( element != null && shouldExist )
        {
            counter.increaseCount();
        }
        if ( element == null && shouldExist )
        {
            element = factory.element( name, parent.getNamespace() );
            insertAtPreferredLocation( parent, element, counter );
            counter.increaseCount();
        }
        if ( !shouldExist && element != null )
        {
            int index = parent.indexOf( element );
            if ( index > 0 )
            {
                Content previous = parent.getContent( index - 1 );
                if ( previous instanceof Text )
                {
                    Text txt = (Text) previous;
                    if ( txt.getTextTrim().length() == 0 )
                    {
                        parent.removeContent( txt );
                    }
                }
            }
            parent.removeContent( element );
        }
        return element;
    }

    /**
     * Method updateExclusion
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateExclusion( Exclusion value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
    }

    /**
     * Method updateExtension
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateExtension( Extension value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
    }

    /**
     * Method updateFileSet
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateFileSet( FileSet value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "directory", value.getDirectory(), null );
            findAndReplaceSimpleLists( innerCount, root, value.getIncludes(), "includes", "include" );
            findAndReplaceSimpleLists( innerCount, root, value.getExcludes(), "excludes", "exclude" );
        }
    }

    /**
     * Method updateIssueManagement
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateIssueManagement( IssueManagement value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "system", value.getSystem(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        }
    }

    /**
     * Method updateLicense
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateLicense( License value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        findAndReplaceSimpleElement( innerCount, root, "distribution", value.getDistribution(), null );
        findAndReplaceSimpleElement( innerCount, root, "comments", value.getComments(), null );
    }

    /**
     * Method updateMailingList
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateMailingList( MailingList value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "subscribe", value.getSubscribe(), null );
        findAndReplaceSimpleElement( innerCount, root, "unsubscribe", value.getUnsubscribe(), null );
        findAndReplaceSimpleElement( innerCount, root, "post", value.getPost(), null );
        findAndReplaceSimpleElement( innerCount, root, "archive", value.getArchive(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getOtherArchives(), "otherArchives", "otherArchive" );
    }

    /**
     * Method updateModel
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateModel( Model value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        updateParent( value.getParent(), "parent", innerCount, root );
        findAndReplaceSimpleElement( innerCount, root, "modelVersion", value.getModelVersion(), null );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "packaging", value.getPackaging(), "jar" );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
        findAndReplaceSimpleElement( innerCount, root, "description", value.getDescription(), null );
        findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        updatePrerequisites( value.getPrerequisites(), "prerequisites", innerCount, root );
        updateIssueManagement( value.getIssueManagement(), "issueManagement", innerCount, root );
        updateCiManagement( value.getCiManagement(), "ciManagement", innerCount, root );
        findAndReplaceSimpleElement( innerCount, root, "inceptionYear", value.getInceptionYear(), null );
        iterateMailingList( innerCount, root, value.getMailingLists(), "mailingLists", "mailingList" );
        iterateDeveloper( innerCount, root, value.getDevelopers(), "developers", "developer" );
        iterateContributor( innerCount, root, value.getContributors(), "contributors", "contributor" );
        iterateLicense( innerCount, root, value.getLicenses(), "licenses", "license" );
        updateScm( value.getScm(), "scm", innerCount, root );
        updateOrganization( value.getOrganization(), "organization", innerCount, root );
        updateBuild( value.getBuild(), "build", innerCount, root );
        iterateProfile( innerCount, root, value.getProfiles(), "profiles", "profile" );
        findAndReplaceSimpleLists( innerCount, root, value.getModules(), "modules", "module" );
        iterateRepository( innerCount, root, value.getRepositories(), "repositories", "repository" );
        iterateRepository( innerCount, root, value.getPluginRepositories(), "pluginRepositories", "pluginRepository" );
        iterateDependency( innerCount, root, value.getDependencies(), "dependencies", "dependency" );
        findAndReplaceXpp3DOM( innerCount, root, "reports", (Xpp3Dom) value.getReports() );
        updateReporting( value.getReporting(), "reporting", innerCount, root );
        updateDependencyManagement( value.getDependencyManagement(), "dependencyManagement", innerCount, root );
        updateDistributionManagement( value.getDistributionManagement(), "distributionManagement", innerCount, root );
        findAndReplaceProperties( innerCount, root, "properties", value.getProperties() );
    }

    /**
     * Method updateModelBase
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    //CHECKSTYLE_OFF: LineLength
    protected void updateModelBase( ModelBase value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleLists( innerCount, root, value.getModules(), "modules", "module" );
            iterateRepository( innerCount, root, value.getRepositories(), "repositories", "repository" );
            iterateRepository( innerCount, root, value.getPluginRepositories(), "pluginRepositories",
                               "pluginRepository" );
            iterateDependency( innerCount, root, value.getDependencies(), "dependencies", "dependency" );
            findAndReplaceXpp3DOM( innerCount, root, "reports", (Xpp3Dom) value.getReports() );
            updateReporting( value.getReporting(), "reporting", innerCount, root );
            updateDependencyManagement( value.getDependencyManagement(), "dependencyManagement", innerCount, root );
            updateDistributionManagement( value.getDistributionManagement(), "distributionManagement", innerCount, root );
            findAndReplaceProperties( innerCount, root, "properties", value.getProperties() );
        }
    }
    //CHECKSTYLE_ON: LineLength

    /**
     * Method updateNotifier
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    //CHECKSTYLE_OFF: LineLength
    protected void updateNotifier( Notifier value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "type", value.getType(), "mail" );
        findAndReplaceSimpleElement( innerCount, root, "sendOnError",
                                     value.isSendOnError() ? null : String.valueOf( value.isSendOnError() ), "true" );
        findAndReplaceSimpleElement( innerCount, root, "sendOnFailure",
                                     value.isSendOnFailure() ? null : String.valueOf( value.isSendOnFailure() ), "true" );
        findAndReplaceSimpleElement( innerCount, root, "sendOnSuccess",
                                     value.isSendOnSuccess() ? null : String.valueOf( value.isSendOnSuccess() ), "true" );
        findAndReplaceSimpleElement( innerCount, root, "sendOnWarning",
                                     value.isSendOnWarning() ? null : String.valueOf( value.isSendOnWarning() ), "true" );
        findAndReplaceSimpleElement( innerCount, root, "address", value.getAddress(), null );
        findAndReplaceProperties( innerCount, root, "configuration", value.getConfiguration() );
    }
    //CHECKSTYLE_ON: LineLength

    /**
     * Method updateOrganization
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateOrganization( Organization value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        }
    }

    /**
     * Method updateParent
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateParent( Parent value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
            findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
            findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
            findAndReplaceSimpleElement( innerCount, root, "relativePath", value.getRelativePath(), "../pom.xml" );
        }
    }

    /**
     * Method updatePatternSet
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePatternSet( PatternSet value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleLists( innerCount, root, value.getIncludes(), "includes", "include" );
            findAndReplaceSimpleLists( innerCount, root, value.getExcludes(), "excludes", "exclude" );
        }
    }

    /**
     * Method updatePlugin
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePlugin( Plugin value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), "org.apache.maven.plugins" );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
        findAndReplaceSimpleElement( innerCount, root, "extensions",
                                     !value.isExtensions() ? null : String.valueOf( value.isExtensions() ), "false" );
        iteratePluginExecution( innerCount, root, value.getExecutions(), "executions", "execution" );
        iterateDependency( innerCount, root, value.getDependencies(), "dependencies", "dependency" );
        findAndReplaceXpp3DOM( innerCount, root, "goals", (Xpp3Dom) value.getGoals() );
        findAndReplaceSimpleElement( innerCount, root, "inherited", value.getInherited(), null );
        findAndReplaceXpp3DOM( innerCount, root, "configuration", (Xpp3Dom) value.getConfiguration() );
    }

    /**
     * Method updatePluginConfiguration
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    //CHECKSTYLE_OFF: LineLength
    protected void updatePluginConfiguration( PluginConfiguration value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            updatePluginManagement( value.getPluginManagement(), "pluginManagement", innerCount, root );
            iteratePlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }
    //CHECKSTYLE_ON: LineLength

    /**
     * Method updatePluginContainer
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePluginContainer( PluginContainer value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            iteratePlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }

    /**
     * Method updatePluginExecution
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePluginExecution( PluginExecution value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), "default" );
        findAndReplaceSimpleElement( innerCount, root, "phase", value.getPhase(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getGoals(), "goals", "goal" );
        findAndReplaceSimpleElement( innerCount, root, "inherited", value.getInherited(), null );
        findAndReplaceXpp3DOM( innerCount, root, "configuration", (Xpp3Dom) value.getConfiguration() );
    }

    /**
     * Method updatePluginManagement
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePluginManagement( PluginManagement value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            iteratePlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }

    /**
     * Method updatePrerequisites
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updatePrerequisites( Prerequisites value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "maven", value.getMaven(), "2.0" );
        }
    }

    /**
     * Method updateProfile
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateProfile( Profile value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), "default" );
        // updateActivation( value.getActivation(), "activation", innerCount, root);
        updateBuildBase( value.getBuild(), "build", innerCount, root );
        findAndReplaceSimpleLists( innerCount, root, value.getModules(), "modules", "module" );
        iterateRepository( innerCount, root, value.getRepositories(), "repositories", "repository" );
        iterateRepository( innerCount, root, value.getPluginRepositories(), "pluginRepositories", "pluginRepository" );
        iterateDependency( innerCount, root, value.getDependencies(), "dependencies", "dependency" );
        findAndReplaceXpp3DOM( innerCount, root, "reports", (Xpp3Dom) value.getReports() );
        updateReporting( value.getReporting(), "reporting", innerCount, root );
        updateDependencyManagement( value.getDependencyManagement(), "dependencyManagement", innerCount, root );
        updateDistributionManagement( value.getDistributionManagement(), "distributionManagement", innerCount, root );
        findAndReplaceProperties( innerCount, root, "properties", value.getProperties() );
    }

    /**
     * Method updateRelocation
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateRelocation( Relocation value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), null );
            findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
            findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
            findAndReplaceSimpleElement( innerCount, root, "message", value.getMessage(), null );
        }
    }

    /**
     * Method updateReportPlugin
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateReportPlugin( ReportPlugin value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "groupId", value.getGroupId(), "org.apache.maven.plugins" );
        findAndReplaceSimpleElement( innerCount, root, "artifactId", value.getArtifactId(), null );
        findAndReplaceSimpleElement( innerCount, root, "version", value.getVersion(), null );
        findAndReplaceSimpleElement( innerCount, root, "inherited", value.getInherited(), null );
        findAndReplaceXpp3DOM( innerCount, root, "configuration", (Xpp3Dom) value.getConfiguration() );
        iterateReportSet( innerCount, root, value.getReportSets(), "reportSets", "reportSet" );
    }

    /**
     * Method updateReportSet
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateReportSet( ReportSet value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), "default" );
        findAndReplaceXpp3DOM( innerCount, root, "configuration", (Xpp3Dom) value.getConfiguration() );
        findAndReplaceSimpleElement( innerCount, root, "inherited", value.getInherited(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getReports(), "reports", "report" );
    }

    /**
     * Method updateReporting
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateReporting( Reporting value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "excludeDefaults", !value.isExcludeDefaults() ? null
                            : String.valueOf( value.isExcludeDefaults() ), "false" );
            findAndReplaceSimpleElement( innerCount, root, "outputDirectory", value.getOutputDirectory(), null );
            iterateReportPlugin( innerCount, root, value.getPlugins(), "plugins", "plugin" );
        }
    }

    /**
     * Method updateRepository
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateRepository( Repository value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        updateRepositoryPolicy( value.getReleases(), "releases", innerCount, root );
        updateRepositoryPolicy( value.getSnapshots(), "snapshots", innerCount, root );
        findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), null );
        findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
        findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        findAndReplaceSimpleElement( innerCount, root, "layout", value.getLayout(), "default" );
    }

    /**
     * Method updateRepositoryBase
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateRepositoryBase( RepositoryBase value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), null );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
            findAndReplaceSimpleElement( innerCount, root, "layout", value.getLayout(), "default" );
        }
    }

    /**
     * Method updateRepositoryPolicy
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateRepositoryPolicy( RepositoryPolicy value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "enabled",
                                         value.isEnabled() ? null : String.valueOf( value.isEnabled() ), "true" );
            findAndReplaceSimpleElement( innerCount, root, "updatePolicy", value.getUpdatePolicy(), null );
            findAndReplaceSimpleElement( innerCount, root, "checksumPolicy", value.getChecksumPolicy(), null );
        }
    }

    /**
     * Method updateResource
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateResource( Resource value, String xmlTag, Counter counter, Element element )
    {
        Element root = element;
        Counter innerCount = new Counter( counter.getDepth() + 1 );
        findAndReplaceSimpleElement( innerCount, root, "targetPath", value.getTargetPath(), null );
        findAndReplaceSimpleElement( innerCount, root, "filtering",
                                     !value.isFiltering() ? null : String.valueOf( value.isFiltering() ), "false" );
        findAndReplaceSimpleElement( innerCount, root, "directory", value.getDirectory(), null );
        findAndReplaceSimpleLists( innerCount, root, value.getIncludes(), "includes", "include" );
        findAndReplaceSimpleLists( innerCount, root, value.getExcludes(), "excludes", "exclude" );
    }

    /**
     * Method updateScm
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateScm( Scm value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            //CHECKSTYLE_OFF: LineLength

            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "connection", value.getConnection(), null );
            findAndReplaceSimpleElement( innerCount, root, "developerConnection", value.getDeveloperConnection(), null );
            findAndReplaceSimpleElement( innerCount, root, "tag", value.getTag(), "HEAD" );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );

            //CHECKSTYLE_ON: LineLength
        }
    }

    /**
     * Method updateSite
     *
     * @param value The value.
     * @param element {@link Element}
     * @param counter {@link Counter}
     * @param xmlTag The XMLTag.
     */
    protected void updateSite( Site value, String xmlTag, Counter counter, Element element )
    {
        boolean shouldExist = value != null;
        Element root = updateElement( counter, element, xmlTag, shouldExist );
        if ( shouldExist )
        {
            Counter innerCount = new Counter( counter.getDepth() + 1 );
            findAndReplaceSimpleElement( innerCount, root, "id", value.getId(), null );
            findAndReplaceSimpleElement( innerCount, root, "name", value.getName(), null );
            findAndReplaceSimpleElement( innerCount, root, "url", value.getUrl(), null );
        }
    }

    /**
     * Method write
     *
     * @param project {@link Model}
     * @param stream {@link OutputStream}
     * @param document {@link Document}
     * @deprecated
     * @throws IOException in case of an error.
     */
    public void write( Model project, Document document, OutputStream stream )
        throws IOException
    {
        updateModel( project, "project", new Counter( 0 ), document.getRootElement() );
        XMLOutputter outputter = new XMLOutputter();
        Format format = Format.getPrettyFormat();
        format.setIndent( "    " ).setLineSeparator( System.getProperty( "line.separator" ) );
        outputter.setFormat( format );
        outputter.output( document, stream );
    }

    /**
     * Method write
     *
     * @param project {@link Model}
     * @param writer {@link OutputStreamWriter}
     * @param document {@link Document}
     * @throws IOException in case of an error.
     */
    public void write( Model project, Document document, OutputStreamWriter writer )
        throws IOException
    {
        Format format = Format.getRawFormat();
        format.setEncoding( writer.getEncoding() ).setLineSeparator( System.getProperty( "line.separator" ) );
        write( project, document, writer, format );
    }

    /**
     * Method write
     *
     * @param project {@link Model}
     * @param jdomFormat {@link Format}
     * @param writer {@link Writer}
     * @param document {@link Document}
     * @throws IOException in case of an error.
     */
    public void write( Model project, Document document, Writer writer, Format jdomFormat )
        throws IOException
    {
        updateModel( project, "project", new Counter( 0 ), document.getRootElement() );
        XMLOutputter outputter = new XMLOutputter();
        outputter.setFormat( jdomFormat );
        outputter.output( document, writer );
    }

}
