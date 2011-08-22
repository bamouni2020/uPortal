/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.layout.dlm;

import java.io.StringWriter;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import net.sf.ehcache.Ehcache;

import org.apache.commons.lang.Validate;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.io.DOMReader;
import org.dom4j.io.DOMWriter;
import org.dom4j.io.DocumentSource;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.jasig.portal.EntityIdentifier;
import org.jasig.portal.IUserIdentityStore;
import org.jasig.portal.IUserProfile;
import org.jasig.portal.PortalException;
import org.jasig.portal.RDBMServices;
import org.jasig.portal.UserProfile;
import org.jasig.portal.io.xml.IPortalDataHandlerService;
import org.jasig.portal.layout.LayoutStructure;
import org.jasig.portal.layout.StructureParameter;
import org.jasig.portal.layout.StylesheetUserPreferencesImpl;
import org.jasig.portal.layout.dao.IStylesheetUserPreferencesDao;
import org.jasig.portal.layout.om.IStylesheetDescriptor;
import org.jasig.portal.layout.om.IStylesheetUserPreferences;
import org.jasig.portal.layout.simple.RDBMUserLayoutStore;
import org.jasig.portal.portlet.dao.IPortletEntityDao;
import org.jasig.portal.portlet.dao.jpa.PortletPreferenceImpl;
import org.jasig.portal.portlet.om.IPortletDefinition;
import org.jasig.portal.portlet.om.IPortletDefinitionId;
import org.jasig.portal.portlet.om.IPortletDefinitionParameter;
import org.jasig.portal.portlet.om.IPortletDescriptorKey;
import org.jasig.portal.portlet.om.IPortletEntity;
import org.jasig.portal.portlet.om.IPortletPreference;
import org.jasig.portal.portlet.om.IPortletType;
import org.jasig.portal.portlet.om.PortletLifecycleState;
import org.jasig.portal.portlet.registry.IPortletEntityRegistry;
import org.jasig.portal.properties.PropertiesManager;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.security.provider.BrokenSecurityContext;
import org.jasig.portal.security.provider.PersonImpl;
import org.jasig.portal.utils.DocumentFactory;
import org.jasig.portal.utils.Tuple;
import org.jasig.portal.xml.XmlUtilities;
import org.jasig.portal.xml.XmlUtilitiesImpl;
import org.jasig.portal.xml.xpath.XPathOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class extends RDBMUserLayoutStore and implements instantiating and
 * storing layouts that conform to the design of the distribute layout
 * management system. These layouts consist of two types: layout fragments
 * that are the layouts owned by a user specified in dlm.xml, and composite
 * view layouts which represent regular users with zero or more UI elements
 * incorporated from layout fragments. Only a user's personal layout fragment
 * is
 *
 * @version $Revision$ $Date$
 * @since uPortal 2.5
 */
public class RDBMDistributedLayoutStore extends RDBMUserLayoutStore {
    public static final String RCS_ID = "@(#) $Header$";
    private static final Log LOG = LogFactory.getLog(RDBMDistributedLayoutStore.class);

    private final static Pattern USER_NODE_PATTERN = Pattern.compile("\\A([a-zA-Z]\\d*)\\z");
    private final static Pattern DLM_NODE_PATTERN = Pattern.compile("u(\\d+)l\\d+([ns]\\d+)");

    private String systemDefaultUser = null;
    private boolean systemDefaultUserLoaded = false;

    private ConfigurationLoader configurationLoader;
    private FragmentActivator fragmentActivator;

    private Ehcache fragmentNodeInfoCache;

    static final String TEMPLATE_USER_NAME = "org.jasig.portal.services.Authentication.defaultTemplateUserName";

    // Used in Import/Export operations
    private final org.dom4j.DocumentFactory fac = new org.dom4j.DocumentFactory();
    private final DOMReader reader = new DOMReader();
    private final DOMWriter writer = new DOMWriter();
    private IUserIdentityStore userIdentityStore;
    private IStylesheetUserPreferencesDao stylesheetUserPreferencesDao;
    private XPathOperations xPathOperations;
    private XmlUtilities xmlUtilities;
    private IPortletEntityRegistry portletEntityRegistry;
    private IPortletEntityDao portletEntityDao;
    private IPortalDataHandlerService portalDataHandlerService;

    @Autowired
	public void setPortletEntityRegistry(IPortletEntityRegistry portletEntityRegistry) {
		this.portletEntityRegistry = portletEntityRegistry;
	}

	@Autowired
	public void setPortalDataHandlerService(IPortalDataHandlerService portalDataHandlerService) {
		this.portalDataHandlerService = portalDataHandlerService;
	}

	@Autowired
    public void setPortletEntityDao(@Qualifier("transient") IPortletEntityDao portletEntityDao) {
        this.portletEntityDao = portletEntityDao;
    }

    @Autowired
    public void setXmlUtilities(XmlUtilities xmlUtilities) {
        this.xmlUtilities = xmlUtilities;
    }

    @Autowired
    public void setXPathOperations(XPathOperations xPathOperations) {
        this.xPathOperations = xPathOperations;
    }

    @Autowired
    public void setIdentityStore(IUserIdentityStore identityStore) {
        this.userIdentityStore = identityStore;
    }

    @Autowired
    public void setStylesheetUserPreferencesDao(IStylesheetUserPreferencesDao stylesheetUserPreferencesDao) {
        this.stylesheetUserPreferencesDao = stylesheetUserPreferencesDao;
    }

    @Autowired
    public void setFragmentNodeInfoCache(
            @Qualifier("org.jasig.portal.layout.dlm.RDBMDistributedLayoutStore.fragmentNodeInfoCache")
            Ehcache fragmentNodeInfoCache) {
        this.fragmentNodeInfoCache = fragmentNodeInfoCache;
    }


    /**
     * Method for acquiring copies of fragment layouts to assist in debugging.
     * No infrastructure code calls this but channels designed to expose the
     * structure of the cached fragments use this to obtain copies.
     * @return Map
     */
    public Map<String, Document> getFragmentLayoutCopies()

    {

        final FragmentActivator activator = this.getFragmentActivator();

        final Map<String, Document> layouts = new HashMap<String, Document>();

        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();
        for (final FragmentDefinition fragmentDefinition : definitions) {
            final Document layout = DocumentFactory.getThreadDocument();
            final UserView userView = activator.getUserView(fragmentDefinition);
            if (userView == null) {
                this.log.warn("No UserView found for FragmentDefinition " + fragmentDefinition.getName()
                        + ", it will be skipped.");
                continue;
            }
            final Node copy = layout.importNode(userView.layout.getDocumentElement(), true);
            layout.appendChild(copy);
            layouts.put(fragmentDefinition.getOwnerId(), layout);
        }
        return layouts;
    }

    @Autowired
    public void setConfigurationLoader(ConfigurationLoader configurationLoader) {
        this.configurationLoader = configurationLoader;
    }

    @Autowired
    public void setFragmentActivator(FragmentActivator fragmentActivator) {
        this.fragmentActivator = fragmentActivator;
    }

    private FragmentActivator getFragmentActivator() {
        return this.fragmentActivator;
    }

    protected IStylesheetUserPreferences loadDistributedStylesheetUserPreferences(IPerson person, IUserProfile profile,
            long stylesheetDescriptorId, Set<String> fragmentNames) {
        if (this.isFragmentOwner(person)) {
            return null;
        }

        final IStylesheetDescriptor stylesheetDescriptor = this.stylesheetDescriptorDao
                .getStylesheetDescriptor(stylesheetDescriptorId);
        final IStylesheetUserPreferences stylesheetUserPreferences = this.stylesheetUserPreferencesDao
                .getStylesheetUserPreferences(stylesheetDescriptor, person, profile);

        final IStylesheetUserPreferences distributedStylesheetUserPreferences = new StylesheetUserPreferencesImpl(
                stylesheetDescriptorId);

        final FragmentActivator fragmentActivator = this.getFragmentActivator();

        for (final String fragmentOwnerId : fragmentNames) {
            final FragmentDefinition fragmentDefinition = this.configurationLoader.getFragmentByName(fragmentOwnerId);

            //UserView may be missing if the fragment isn't defined correctly
            final UserView userView = fragmentActivator.getUserView(fragmentDefinition);
            if (userView == null) {
                this.log.warn("No UserView is present for fragment " + fragmentDefinition.getName()
                        + " it will be skipped when loading distributed stylesheet user preferences");
                continue;
            }

            //IStylesheetUserPreferences only exist if something was actually set
            final IStylesheetUserPreferences fragmentStylesheetUserPreferences = this.stylesheetUserPreferencesDao
                    .getStylesheetUserPreferences(stylesheetDescriptor, userView.getUserId(), userView.profileId);
            if (fragmentStylesheetUserPreferences == null) {
                continue;
            }

            //Get the info needed to DLMify node IDs
            final Element root = userView.layout.getDocumentElement();
            final String labelBase = root.getAttribute(Constants.ATT_ID);

            boolean modified = false;

            //Copy all of the fragment preferences into the distributed preferences
            final Map<String, Map<String, String>> allLayoutAttributes = fragmentStylesheetUserPreferences
                    .getAllLayoutAttributes();
            for (final Map.Entry<String, Map<String, String>> layoutNodeAttributesEntry : allLayoutAttributes
                    .entrySet()) {
                String nodeId = layoutNodeAttributesEntry.getKey();

                if (!nodeId.startsWith(Constants.FRAGMENT_ID_USER_PREFIX)) {
                    nodeId = labelBase + nodeId;
                }

                final Map<String, String> layoutAttributes = layoutNodeAttributesEntry.getValue();
                for (final Map.Entry<String, String> layoutAttributesEntry : layoutAttributes.entrySet()) {
                    final String name = layoutAttributesEntry.getKey();
                    final String value = layoutAttributesEntry.getValue();

                    //Fragmentize the nodeId here
                    distributedStylesheetUserPreferences.setLayoutAttribute(nodeId, name, value);

                    //Clean out user preferences data that matches data from the fragment.
                    if (stylesheetUserPreferences != null) {
                        final String userValue = stylesheetUserPreferences.getLayoutAttribute(nodeId, name);
                        if (userValue != null && userValue.equals(value)) {
                            stylesheetUserPreferences.removeLayoutAttribute(nodeId, name);
                            EditManager.removePreferenceDirective(person, nodeId, name);
                            modified = true;
                        }
                    }
                }
            }

            if (modified) {
                this.stylesheetUserPreferencesDao.storeStylesheetUserPreferences(stylesheetUserPreferences);
            }
        }

        return distributedStylesheetUserPreferences;
    }

    @Override
    public double getFragmentPrecedence(int index) {
        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();
        if (index < 0 || index > definitions.size() - 1) {
            return 0;
        }

        // must pass through the array looking for the fragment with this
        // index since the array was sorted by precedence and then index
        // within precedence.
        for (final FragmentDefinition fragmentDefinition : definitions) {
            if (fragmentDefinition.getIndex() == index) {
                return fragmentDefinition.getPrecedence();
            }
        }
        return 0; // should never get here.
    }

    /**
       Returns the layout for a user decorated with any specified decorator.
       The layout returned is a composite layout for non fragment owners
       and a regular layout for layout owners. A composite layout is made up
       of layout pieces from potentially multiple incorporated layouts. If
       no layouts are defined then the composite layout will be the same as
       the user's personal layout fragment or PLF, the one holding only those
       UI elements that they own or incorporated elements that they have been
       allowed to changed.
     */
    @Override
    public DistributedUserLayout getUserLayout(IPerson person, IUserProfile profile)

    {

        final DistributedUserLayout layout = this._getUserLayout(person, profile);

        return layout;
    }

    private boolean layoutExistsForUser(IPerson person) {

        // Assertions.
        if (person == null) {
            final String msg = "Argument 'person' cannot be null.";
            throw new IllegalArgumentException(msg);
        }

        final int struct_count = this.jdbcOperations
                .queryForInt("SELECT COUNT(*) FROM up_layout_struct WHERE user_id = ?", person.getID());
        return struct_count == 0 ? false : true;

    }

    @Override
    public org.dom4j.Element exportLayout(IPerson person, IUserProfile profile) {
        org.dom4j.Element layout = getExportLayoutDom(person, profile);
        
        final int userId = person.getID();
        final String userName = person.getUserName();
        final Set<IPortletEntity> portletEntities = this.portletEntityDao.getPortletEntitiesForUser(userId);
        
        org.dom4j.Element preferencesElement = null;
        for (final Iterator<IPortletEntity> entityItr = portletEntities.iterator(); entityItr.hasNext(); ) {
            final IPortletEntity portletEntity = entityItr.next();
            final List<IPortletPreference> preferencesList = portletEntity.getPortletPreferences();
            
            //Only bother with entities that have preferences
            if (!preferencesList.isEmpty()) {
                final String layoutNodeId = portletEntity.getLayoutNodeId();
                final String[] dlmPathref = this.getDlmPathref(userName, userId, layoutNodeId, layout);
                if (dlmPathref == null || dlmPathref.length != 3 || dlmPathref[0] == null || dlmPathref[1] == null || dlmPathref[2] == null) {
                    log.warn(portletEntity + " in user " + userName + "'s layout has no corresponding layout or portlet information and will be ignored");
                    continue;
                }
                
                for (final IPortletPreference portletPreference : preferencesList) {
                    if (preferencesElement == null) {
                        if (layout == null) {
                            final org.dom4j.Document layoutDoc = new org.dom4j.DocumentFactory().createDocument();
                            layout = layoutDoc.addElement("layout");
                            layout.addNamespace("dlm", "http://www.uportal.org/layout/dlm");
                        }
                        preferencesElement = layout.addElement("preferences");
                    }
                    
                    final org.dom4j.Element preferenceEntry = preferencesElement.addElement("entry");
                    preferenceEntry.addAttribute("entity", dlmPathref[0] + ":" + dlmPathref[1]);
                    preferenceEntry.addAttribute("channel", dlmPathref[2]);
                    preferenceEntry.addAttribute("name", portletPreference.getName());
                    
                    for (final String value : portletPreference.getValues()) {
                        final org.dom4j.Element valueElement = preferenceEntry.addElement("value");
                        valueElement.setText(value);
                    }
                }
            }
        }
        
        if (layout != null) {
        	layout.addAttribute("script", "classpath://org/jasig/portal/io/import-layout_v3-2.crn");
        	layout.addAttribute("username", userName);
        }
        
        return layout;
    }

    private org.dom4j.Element getExportLayoutDom(IPerson person, IUserProfile profile) {
        if (!this.layoutExistsForUser(person)) {
            return null;
        }

        org.dom4j.Document layoutDoc = null;
        try {
            final Document layoutDom = this._safeGetUserLayout(person, profile);
            person.setAttribute(Constants.PLF, layoutDom);
            layoutDoc = this.reader.read(layoutDom);
        }
        catch (final Throwable t) {
            final String msg = "Unable to obtain layout & profile for user '" + person.getUserName() + "', profileId "
                    + profile.getProfileId();
            throw new RuntimeException(msg, t);
        }

        if (this.log.isDebugEnabled()) {
            // Write out this version of the layout to the log for dev purposes...
            final StringWriter str = new StringWriter();
            final XMLWriter xml = new XMLWriter(str, new OutputFormat("  ", true));
            try {
                xml.write(layoutDoc);
                xml.close();
            }
            catch (final Throwable t) {
                throw new RuntimeException("Failed to write the layout for user '" + person.getUserName()
                        + "' to the DEBUG log", t);
            }
            this.log.debug("Layout for user:  " + person.getUserName() + "\n" + str.getBuffer().toString());
        }

        /*
         * Clean up the DOM for export.
         */

        // (1) Add structure & theme attributes...
        final int structureStylesheetId = profile.getStructureStylesheetId();
        this.addStylesheetUserPreferencesAttributes(person, profile, layoutDoc, structureStylesheetId, "structure");

        final int themeStylesheetId = profile.getThemeStylesheetId();
        this.addStylesheetUserPreferencesAttributes(person, profile, layoutDoc, themeStylesheetId, "theme");

        // (2) Remove locale info...
        final Iterator<org.dom4j.Attribute> locale = (Iterator<org.dom4j.Attribute>) layoutDoc.selectNodes("//@locale")
                .iterator();
        while (locale.hasNext()) {
            final org.dom4j.Attribute loc = locale.next();
            loc.getParent().remove(loc);
        }

        // (3) Scrub unnecessary channel information...
        for (final Iterator<org.dom4j.Element> orphanedChannels = (Iterator<org.dom4j.Element>) layoutDoc
                .selectNodes("//channel[@fname = '']").iterator(); orphanedChannels.hasNext();) {
            // These elements represent UP_LAYOUT_STRUCT rows where the 
            // CHAN_ID field was not recognized by ChannelRegistryStore;  
            // best thing to do is remove the elements...
            final org.dom4j.Element ch = orphanedChannels.next();
            ch.getParent().remove(ch);
        }
        final List<String> channelAttributeWhitelist = Arrays.asList(new String[] { "fname", "unremovable", "hidden",
                "immutable", "ID", "dlm:plfID", "dlm:moveAllowed", "dlm:deleteAllowed" });
        final Iterator<org.dom4j.Element> channels = (Iterator<org.dom4j.Element>) layoutDoc.selectNodes("//channel")
                .iterator();
        while (channels.hasNext()) {
            final org.dom4j.Element oldCh = channels.next();
            final org.dom4j.Element parent = oldCh.getParent();
            final org.dom4j.Element newCh = this.fac.createElement("channel");
            for (final String aName : channelAttributeWhitelist) {
                final org.dom4j.Attribute a = (org.dom4j.Attribute) oldCh.selectSingleNode("@" + aName);
                if (a != null) {
                    newCh.addAttribute(a.getQName(), a.getValue());
                }
            }
            parent.elements().add(parent.elements().indexOf(oldCh), newCh);
            parent.remove(oldCh);
        }

        // (4) Convert internal DLM noderefs to external form (pathrefs)...
        for (final Iterator<org.dom4j.Attribute> origins = (Iterator<org.dom4j.Attribute>) layoutDoc
                .selectNodes("//@dlm:origin").iterator(); origins.hasNext();) {
            final org.dom4j.Attribute org = origins.next();
            final String[] pathTokens = this.getDlmPathref((String) person.getAttribute(IPerson.USERNAME),
                    person.getID(),
                    org.getValue(),
                    layoutDoc.getRootElement());
            if (pathTokens != null) {
                // Change the value only if we have a valid pathref...
                org.setValue(pathTokens[0] + ":" + pathTokens[1]);
            }
            else {
                if (this.log.isWarnEnabled()) {
                    this.log.warn("Layout element '" + org.getUniquePath() + "' from user '"
                            + person.getAttribute(IPerson.USERNAME) + "' failed to match noderef '" + org.getValue()
                            + "'");
                }
            }
        }
        for (final Iterator<org.dom4j.Attribute> it = (Iterator<org.dom4j.Attribute>) layoutDoc
                .selectNodes("//@dlm:target").iterator(); it.hasNext();) {
            final org.dom4j.Attribute target = it.next();
            final String[] pathTokens = this.getDlmPathref((String) person.getAttribute(IPerson.USERNAME),
                    person.getID(),
                    target.getValue(),
                    layoutDoc.getRootElement());
            if (pathTokens != null) {
                // Change the value only if we have a valid pathref...
                target.setValue(pathTokens[0] + ":" + pathTokens[1]);
            }
            else {
                if (this.log.isWarnEnabled()) {
                    this.log.warn("Layout element '" + target.getUniquePath() + "' from user '"
                            + person.getAttribute(IPerson.USERNAME) + "' failed to match noderef '" + target.getValue()
                            + "'");
                }
            }
        }
        for (final Iterator<org.dom4j.Attribute> names = (Iterator<org.dom4j.Attribute>) layoutDoc
                .selectNodes("//dlm:*/@name").iterator(); names.hasNext();) {
            final org.dom4j.Attribute n = names.next();
            if (n.getValue() == null || n.getValue().trim().length() == 0) {
                // Outer <dlm:positionSet> elements don't seem to use the name 
                // attribute, though their childern do.  Just skip these so we 
                // don't send a false WARNING.
                continue;
            }
            final String[] pathTokens = this.getDlmPathref((String) person.getAttribute(IPerson.USERNAME),
                    person.getID(),
                    n.getValue(),
                    layoutDoc.getRootElement());
            if (pathTokens != null) {
                // Change the value only if we have a valid pathref...
                n.setValue(pathTokens[0] + ":" + pathTokens[1]);
                // These *may* have fnames...
                if (pathTokens[2] != null && pathTokens[2].trim().length() != 0) {
                    n.getParent().addAttribute("fname", pathTokens[2]);
                }
            }
            else {
                if (this.log.isWarnEnabled()) {
                    this.log.warn("Layout element '" + n.getUniquePath() + "' from user '"
                            + person.getAttribute(IPerson.USERNAME) + "' failed to match noderef '" + n.getValue()
                            + "'");
                }
            }
        }

        // Remove synthetic Ids, but from non-fragment owners only...
        if (!this.isFragmentOwner(person)) {

            /*
             * In the case of fragment owners, the original database Ids allow 
             * us keep (not break) the associations that subscribers have with 
             * nodes on the fragment layout.
             */

            // (5) Remove dlm:plfID...
            for (final Iterator<org.dom4j.Attribute> plfid = (Iterator<org.dom4j.Attribute>) layoutDoc
                    .selectNodes("//@dlm:plfID").iterator(); plfid.hasNext();) {
                final org.dom4j.Attribute plf = plfid.next();
                plf.getParent().remove(plf);
            }

            // (6) Remove database Ids...
            for (final Iterator<org.dom4j.Attribute> ids = (Iterator<org.dom4j.Attribute>) layoutDoc
                    .selectNodes("//@ID").iterator(); ids.hasNext();) {
                final org.dom4j.Attribute a = ids.next();
                a.getParent().remove(a);
            }
        }

        return layoutDoc.getRootElement();
    }

    protected void addStylesheetUserPreferencesAttributes(IPerson person, IUserProfile profile,
            org.dom4j.Document layoutDoc, int stylesheetId, String attributeType) {
        final IStylesheetDescriptor structureStylesheetDescriptor = this.stylesheetDescriptorDao
                .getStylesheetDescriptor(stylesheetId);

        final IStylesheetUserPreferences ssup = this.stylesheetUserPreferencesDao
                .getStylesheetUserPreferences(structureStylesheetDescriptor, person, profile);

        if (ssup != null) {
            final Map<String, Map<String, String>> allLayoutAttributes = ssup.getAllLayoutAttributes();
            for (final Entry<String, Map<String, String>> nodeEntry : allLayoutAttributes.entrySet()) {
                final String nodeId = nodeEntry.getKey();
                final Map<String, String> attributes = nodeEntry.getValue();

                final org.dom4j.Element element = layoutDoc.elementByID(nodeId);
                if (element == null) {
                    this.log.warn("No node with id '" + nodeId + "' found in layout for: " + person.getUserName() + ". Stylesheet user preference layout attributes will be ignored: " + attributes);
                    continue;
                }

                for (final Entry<String, String> attributeEntry : attributes.entrySet()) {
                    final String name = attributeEntry.getKey();
                    final String value = attributeEntry.getValue();

                    if (this.log.isDebugEnabled()) {
                        this.log.debug("Adding structure folder attribute:  name=" + name + ", value=" + value);
                    }
                    final org.dom4j.Element structAttrElement = this.fac.createElement(attributeType + "-attribute");
                    final org.dom4j.Element nameAttribute = structAttrElement.addElement("name");
                    nameAttribute.setText(name);
                    final org.dom4j.Element valueAttribute = structAttrElement.addElement("value");
                    valueAttribute.setText(value);
                    element.elements().add(0, structAttrElement);
                }
            }
        }
        else {
            LOG.debug("no StylesheetUserPreferences found for " + person + ", " + profile);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    @Transactional
    public void importLayout(org.dom4j.Element layout) {
//    	try {
//    	OutputFormat format = OutputFormat.createPrettyPrint();
//        XMLWriter writer = new XMLWriter( System.out, format );
//        writer.write( layout );
//        writer.close();
//    	}catch (Exception e) {
//    		e.printStackTrace();
//    	}
        
        //Get a ref to the prefs element and then remove it from the layout
        final org.dom4j.Node preferencesElement = layout.selectSingleNode("preferences");
        if (preferencesElement != null) {
            preferencesElement.getParent().remove(preferencesElement);
        }
        
        final String ownerUsername = layout.valueOf("@username");
        
        //Get a ref to the profile element and then remove it from the layout
        final org.dom4j.Node profileElement = layout.selectSingleNode("profile");
        if (profileElement != null) {
        	profileElement.getParent().remove(profileElement);
        	
        	final org.dom4j.Document profileDocument = new org.dom4j.DocumentFactory().createDocument();
        	profileDocument.setRootElement((org.dom4j.Element)profileElement);
        	profileDocument.setName(ownerUsername + ".profile");
        	
        	final DocumentSource profileSource = new DocumentSource(profileElement);
			this.portalDataHandlerService.importData(profileSource);
        }
       

        IPerson person = null;
        IUserProfile profile = null;
        try {
            person = new PersonImpl();
            person.setUserName(ownerUsername);
            final int ownerId = this.userIdentityStore.getPortalUID(person);
            if (ownerId == -1) {
                final String msg = "No userId for username=" + ownerUsername;
                throw new RuntimeException(msg);
            }
            person.setID(ownerId);
        }
        catch (final Throwable t) {
            throw new RuntimeException("Unrecognized user " + person.getUserName()
                    + "; you must import users before their layouts.", t);
        }

        try {
	        person.setSecurityContext(new BrokenSecurityContext());
	        profile = this.getUserProfileByFname(person, "default");
        }
        catch (final Throwable t) {
            throw new RuntimeException("Failed to load profile for " + person.getUserName()
                    + "; This user must have a profile for import to continue.", t);
        }

        // (6) Add database Ids & (5) Add dlm:plfID ...
        int nextId = 1;
        for (final Iterator<org.dom4j.Element> it = (Iterator<org.dom4j.Element>) layout
                .selectNodes("folder | dlm:* | channel").iterator(); it.hasNext();) {
            nextId = this.addIdAttributesIfNecessary(it.next(), nextId);
        }
        // Now update UP_USER...
        this.jdbcOperations.update("UPDATE up_user SET next_struct_id = ? WHERE user_id = ?", nextId, person.getID());

        // (4) Convert external DLM pathrefs to internal form (noderefs)...
        for (final Iterator<org.dom4j.Attribute> itr = (Iterator<org.dom4j.Attribute>) layout
                .selectNodes("//@dlm:origin").iterator(); itr.hasNext();) {
            final org.dom4j.Attribute a = itr.next();
            final String noderef = this.getDlmNoderef(ownerUsername, a.getValue(), null, true, layout);
            if (noderef != null) {
                // Change the value only if we have a valid pathref...
                a.setValue(noderef);
                // For dlm:origin only, also use the noderef as the ID attribute...
                a.getParent().addAttribute("ID", noderef);
            }
        }
        for (final Iterator<org.dom4j.Attribute> itr = (Iterator<org.dom4j.Attribute>) layout
                .selectNodes("//@dlm:target").iterator(); itr.hasNext();) {
            final org.dom4j.Attribute a = itr.next();
            final String noderef = this.getDlmNoderef(ownerUsername, a.getValue(), null, true, layout);
            if (noderef != null) {
                // Change the value only if we have a valid pathref...
                a.setValue(noderef);
            }
        }
        for (final Iterator<org.dom4j.Attribute> names = (Iterator<org.dom4j.Attribute>) layout
                .selectNodes("//dlm:*/@name").iterator(); names.hasNext();) {
            final org.dom4j.Attribute a = names.next();
            final org.dom4j.Attribute fname = a.getParent().attribute("fname");
            String noderef = null;
            if (fname != null) {
                noderef = this.getDlmNoderef(ownerUsername, a.getValue(), fname.getValue(), false, layout);
                // Remove the fname attribute now that we're done w/ it...
                fname.getParent().remove(fname);
            }
            else {
                noderef = this.getDlmNoderef(ownerUsername, a.getValue(), null, true, layout);
            }
            if (noderef != null) {
                // Change the value only if we have a valid pathref...
                a.setValue(noderef);
            }
        }

        // (3) Restore chanID attributes on <channel> elements...
        for (final Iterator<org.dom4j.Element> it = (Iterator<org.dom4j.Element>) layout.selectNodes("//channel")
                .iterator(); it.hasNext();) {
            final org.dom4j.Element c = it.next();
            final String fname = c.valueOf("@fname");
            final IPortletDefinition cd = this.portletDefinitionRegistry.getPortletDefinitionByFname(fname);
            if (cd == null) {
                throw new IllegalArgumentException("No published channel for fname=" + fname
                        + " referenced by layout for " + ownerUsername);
            }
            c.addAttribute("chanID", String.valueOf(cd.getPortletDefinitionId().getStringId()));
        }

        // (2) Restore locale info...
        // (This step doesn't appear to be needed for imports)

        // (1) Process structure & theme attributes...
        Document layoutDom = null;
        try {

            // Structure Attributes.
            final boolean saSet = false;

            final int structureStylesheetId = profile.getStructureStylesheetId();
            this.loadStylesheetUserPreferencesAttributes(person, profile, layout, structureStylesheetId, "structure");

            final int themeStylesheetId = profile.getThemeStylesheetId();
            this.loadStylesheetUserPreferencesAttributes(person, profile, layout, themeStylesheetId, "theme");

            // From this point forward we need the user's PLF set as DLM expects it...
            for (final Iterator<org.dom4j.Text> it = (Iterator<org.dom4j.Text>) layout
                    .selectNodes("descendant::text()").iterator(); it.hasNext();) {
                // How many years have we used Java & XML, and this still isn't easy?
                final org.dom4j.Text txt = it.next();
                if (txt.getText().trim().length() == 0) {
                    txt.getParent().remove(txt);
                }
            }

            final org.dom4j.Element copy = layout.createCopy();
            final org.dom4j.Document doc = this.fac.createDocument(copy);
            doc.normalize();
            layoutDom = this.writer.write(doc);
            person.setAttribute(Constants.PLF, layoutDom);

        }
        catch (final Throwable t) {
            throw new RuntimeException("Unable to set UserPreferences for user:  " + person.getUserName(), t);
        }

        // Finally store the layout...
        try {
            this.setUserLayout(person, profile, layoutDom, true, true);
        }
        catch (final Throwable t) {
            final String msg = "Unable to persist layout for user:  " + ownerUsername;
            throw new RuntimeException(msg, t);
        }
        
        if (preferencesElement != null) {
        	final int ownerUserId = this.userIdentityStore.getPortalUserId(ownerUsername);

        	//Delete all existing portlet preferences for the user's layout
        	//TODO this assumes a single layout, when multi-layout support exists portlet entities will need to be re-worked to allow for a layout id to be associated with the entity
        	final Set<IPortletEntity> portletEntities = this.portletEntityDao.getPortletEntitiesForUser(ownerUserId);
        	for (final IPortletEntity portletEntity : portletEntities) {
        		portletEntity.setPortletPreferences(null);
        		
        		if (portletEntityRegistry.shouldBePersisted(portletEntity)) {
        			this.portletEntityDao.updatePortletEntity(portletEntity);
        		}
        		else {
        			this.portletEntityDao.deletePortletEntity(portletEntity);
        		}
			}
        	
            final List<org.dom4j.Element> entries = preferencesElement.selectNodes("entry");
            for (final org.dom4j.Element entry : entries) {
                final String dlmPathRef = entry.attributeValue("entity");
                final String fname = entry.attributeValue("channel");
                final String prefName = entry.attributeValue("name");
                
                final String dlmNoderef = this.getDlmNoderef(person.getUserName(), dlmPathRef, fname, false, layout);
                
                if (dlmNoderef != null && !"".equals(dlmNoderef) && fname != null && !"null".equals(fname)) {
                	final IPortletEntity portletEntity = this.getPortletEntity(fname, dlmNoderef, ownerUserId);
                	final List<IPortletPreference> portletPreferences = portletEntity.getPortletPreferences();
                	
                	final List<org.dom4j.Element> valueElements = entry.selectNodes("value");
                	final List<String> values = new ArrayList<String>(valueElements.size());
                	for (final org.dom4j.Element valueElement : valueElements) {
                		values.add(valueElement.getText());
                	}
                	
                	portletPreferences.add(new PortletPreferenceImpl(prefName, false, values.toArray(new String[values.size()])));
                	
                	this.portletEntityDao.updatePortletEntity(portletEntity);
                }
            }
        }
    }
    
    protected IPortletEntity getPortletEntity(String fName, String layoutNodeId, int userId) {
    	//Try getting the entity
    	final IPortletEntity portletEntity = this.portletEntityDao.getPortletEntity(layoutNodeId, userId);
        if (portletEntity != null) {
            return portletEntity;
        }
        
        //Load the portlet definition
        final IPortletDefinition portletDefinition;
        try {
            portletDefinition = this.portletDefinitionRegistry.getPortletDefinitionByFname(fName);
        }
        catch (Exception e) {
            throw new DataRetrievalFailureException("Failed to retrieve ChannelDefinition for fName='" + fName + "'", e);
        }
        
        //The channel definition for the fName MUST exist for this class to function
        if (portletDefinition == null) {
            throw new EmptyResultDataAccessException("No ChannelDefinition exists for fName='" + fName + "'", 1);
        }
        
        //create the portlet entity
        final IPortletDefinitionId portletDefinitionId = portletDefinition.getPortletDefinitionId();
        return this.portletEntityDao.createPortletEntity(portletDefinitionId, layoutNodeId, userId);
    }

    protected void loadStylesheetUserPreferencesAttributes(IPerson person, IUserProfile profile,
            org.dom4j.Element layout, final int structureStylesheetId, final String nodeType) {

        final IStylesheetDescriptor stylesheetDescriptor = this.stylesheetDescriptorDao
                .getStylesheetDescriptor(structureStylesheetId);
        final List<org.dom4j.Element> structureAttributes = layout.selectNodes("//" + nodeType + "-attribute");

        IStylesheetUserPreferences ssup = this.stylesheetUserPreferencesDao
                .getStylesheetUserPreferences(stylesheetDescriptor, person, profile);
        if (structureAttributes.isEmpty()) {
            if (ssup != null) {
                this.stylesheetUserPreferencesDao.deleteStylesheetUserPreferences(ssup);
            }
        }
        else {
            if (ssup == null) {
                ssup = this.stylesheetUserPreferencesDao.createStylesheetUserPreferences(stylesheetDescriptor,
                        person,
                        profile);
            }

            ssup.clearAllLayoutAttributes();

            for (final org.dom4j.Element structureAttribute : structureAttributes) {
                final org.dom4j.Element layoutElement = structureAttribute.getParent();
                final String nodeId = layoutElement.valueOf("@ID");

                final String name = structureAttribute.valueOf("name");
                final String value = structureAttribute.valueOf("value");

                ssup.setLayoutAttribute(nodeId, name, value);

                // Remove the layout attribute element or DLM fails
                layoutElement.remove(structureAttribute);
            }

            this.stylesheetUserPreferencesDao.storeStylesheetUserPreferences(ssup);
        }
    }

    @SuppressWarnings("unchecked")
    private final int addIdAttributesIfNecessary(org.dom4j.Element e, int nextId) {

        int idAfterThisOne = nextId; // default...
        final org.dom4j.Node idAttribute = e.selectSingleNode("@ID | @dlm:plfID");
        if (idAttribute == null) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("No ID or dlm:plfID attribute for the following node "
                        + "(one will be generated and added):  element" + e.getName() + ", name=" + e.valueOf("@name")
                        + ", fname=" + e.valueOf("@fname"));
            }

            // We need to add an ID to this node...
            char prefix;
            if (e.getName().equals("folder")) {
                prefix = 's';
            }
            else if (e.getName().equals("channel")) {
                prefix = 'n';
            }
            else if (e.getQName().getNamespacePrefix().equals("dlm")) {
                prefix = 'd';
            }
            else {
                throw new RuntimeException("Unrecognized element type:  " + e.getName());
            }

            final String origin = e.valueOf("@dlm:origin");
            // 'origin' may be null if the dlm:origin attribute is an 
            // empty string (which also shouldn't happen);  'origin' 
            // will be zero-length if dlm:origin is not defined...
            if (origin != null && origin.length() != 0) {
                // Add as dlm:plfID, if necessary...
                e.addAttribute("dlm:plfID", prefix + String.valueOf(nextId));
            }
            else {
                // Do the standard thing, if necessary...
                e.addAttribute("ID", prefix + String.valueOf(nextId));
            }

            ++idAfterThisOne;
        }
        else {
            final String id = idAttribute.getText();
            try {
                idAfterThisOne = Integer.parseInt(id.substring(1)) + 1;
            }
            catch (final NumberFormatException nfe) {
                this.log.warn("Could not parse int value from id: " + id + " The next layout id will be: "
                        + idAfterThisOne, nfe);
            }
        }

        // Now check children...
        for (final Iterator<org.dom4j.Element> itr = (Iterator<org.dom4j.Element>) e
                .selectNodes("folder | channel | dlm:*").iterator(); itr.hasNext();) {
            final org.dom4j.Element child = itr.next();
            idAfterThisOne = this.addIdAttributesIfNecessary(child, idAfterThisOne);
        }

        return idAfterThisOne;

    }

    private final String[] getDlmPathref(String layoutOwnerUsername, int layoutOwnerUserId, String dlmNoderef,
            org.dom4j.Element layout) {
        
        /*
         * need to remove "org.dom4j.Element layout" as a paramter and make this method public and
         * on the interface so that external code can call it.
         * 
         * OR
         * 
         * move the preferences export into exportLayout
         */

        Validate.notNull(layoutOwnerUsername, "Argument 'layoutOwnerUsername' cannot be null.");
        Validate.notNull(dlmNoderef, "Argument 'dlmNoderef' cannot be null.");
        

        
        final Matcher dlmNodeMatcher = DLM_NODE_PATTERN.matcher(dlmNoderef);
        if (dlmNodeMatcher.matches()) {
            final String userId = dlmNodeMatcher.group(1);
            final String nodeId = dlmNodeMatcher.group(2);
            
            
            final Tuple<String, DistributedUserLayout> userLayoutInfo = getUserLayout(Integer.valueOf(userId));
            
            if (userLayoutInfo.second == null) {
                this.log.warn("no layout for fragment user '" + userLayoutInfo.first + "' Specified dlmNoderef " + dlmNoderef + " cannot be resolved.");
                return null;
            }
                                
            final Document fragmentLayout = userLayoutInfo.second.getLayout();
            final Node targetElement = this.xPathOperations.evaluate("//*[@ID = $nodeId]", Collections.singletonMap("nodeId", nodeId), fragmentLayout, XPathConstants.NODE);
            
            final String[] rslt = new String[3];
            rslt[0] = userLayoutInfo.first;
            rslt[1] = this.xmlUtilities.getUniqueXPath(targetElement);
            rslt[2] = targetElement.getAttributes().getNamedItem("fname").getTextContent();
            
            return rslt;
        }

        final Matcher userNodeMatcher = USER_NODE_PATTERN.matcher(dlmNoderef);
        if (userNodeMatcher.find()) {
            // We need a pathref based on the new style of layout b/c on 
            // import this users own layout will not be in the database 
            // when the path is computed back to an Id...
            final String structId = userNodeMatcher.group(1);
            final org.dom4j.Node target = layout.selectSingleNode("//*[@ID = '" + structId + "']");
            if (target == null) {
                this.log.warn("no match found on layout for user '" + layoutOwnerUsername + "' for the specified dlmNoderef:  " + dlmNoderef);
                return null;
            }
            
            final String[]  rslt = new String[3];
            rslt[0] = layoutOwnerUsername;
            rslt[1] = target.getUniquePath();
            if (target.getName().equals("channel")) {
                rslt[2] = target.valueOf("@fname");
            }
                
            return rslt;
        }
        
        
        return null;
        
    }
    
    private Tuple<String, DistributedUserLayout> getUserLayout(int userId) {
        final String userName = userIdentityStore.getPortalUserName(userId);
        
        final PersonImpl person = new PersonImpl();
        person.setUserName(userName);
        person.setID(userId);
        person.setSecurityContext(new BrokenSecurityContext());
    
        final UserProfile profile = this.getUserProfileByFname(person, UserProfile.DEFAULT_PROFILE_FNAME);
        final DistributedUserLayout userLayout = this.getUserLayout(person, profile);

        return new Tuple<String, DistributedUserLayout>(userName, userLayout);
    }

    private static final Pattern DLM_PATH_REF_DELIM = Pattern.compile("\\:");
    private final String getDlmNoderef(String layoutOwner, String pathref, String fname, boolean isStructRef,
            org.dom4j.Element layoutElement) {

        Validate.notNull(layoutOwner, "Argument 'layoutOwner' cannot be null.");
        Validate.notNull(pathref, "Argument 'pathref' cannot be null.");

        final String[] pathTokens = DLM_PATH_REF_DELIM.split(pathref);
        if (pathTokens.length <= 1) {
            this.log.warn("Invalid DLM PathRef, no delimiter: " + pathref);
            return "";
        }
        
        if (pathTokens[0].equals(layoutOwner)) {
            // This an internal reference (our own layout);  we have to 
            // use the layoutExment (instead of load-limited-layout) b/c 
            // our layout may not be in the db...
            final org.dom4j.Element target = (org.dom4j.Element) layoutElement.selectSingleNode(pathTokens[1]);
            if (target != null) {
                return target.valueOf("@ID");
            }

            this.log.warn("Unable to resolve pathref '" + pathref + "' for layoutOwner '" + layoutOwner + "'");
            return "";
        }
        
        final String layoutOwnerName = pathTokens[0];
        final String layoutPath = pathTokens[1];
        
        final Integer layoutOwnerUserId = this.userIdentityStore.getPortalUserId(layoutOwnerName);
        if (layoutOwnerUserId == null) {
        	this.log.warn("Unable to resolve pathref '" + pathref + "' for layoutOwner '" + layoutOwner + "', no userId found for userName: " + layoutOwnerName);
            return "";
        }
        
        final Tuple<String, DistributedUserLayout> userLayoutInfo = getUserLayout(layoutOwnerUserId);
        final Document userLayout = userLayoutInfo.second.getLayout();
        
		final Node targetNode = this.xPathOperations.evaluate(layoutPath, userLayout, XPathConstants.NODE);
        if (targetNode == null) {
            this.log.warn("No layout node found for pathref: " + pathref);
            return "";
        }
        
        final NamedNodeMap attributes = targetNode.getAttributes();
        if (fname != null) {
            final Node fnameAttr = attributes.getNamedItem("fname");
            if (fnameAttr == null) {
                this.log.warn("Layout node for pathref does not have fname attribute: " + pathref);
                return "";
            }
            
            final String nodeFname = fnameAttr.getTextContent();
            if (!fname.equals(nodeFname)) {
                this.log.warn("fname '" + nodeFname + "' on layout node not match specified fname '" + fname + "' for pathref: " + pathref);
                return "";
            }
        }
        
        final Node structIdAttr = attributes.getNamedItem("struct-id");
        if (structIdAttr != null) {
	        final String structId = structIdAttr.getTextContent();
	
	        if (isStructRef) {
	            return "u" + layoutOwnerUserId + "l1s" + structId;
	        }
	        
	        return "u" + layoutOwnerUserId + "l1n" + structId;
        }

        final Node idAttr = attributes.getNamedItem("ID");
        return "u" + layoutOwnerUserId + "l1" + idAttr.getTextContent();
    }

    /**
     * Handles locking and identifying proper root and namespaces that used to
     * take place in super class.
     *
     * @param person
     * @param profile
     * @return
     * @
     */
    private Document _safeGetUserLayout(IPerson person, IUserProfile profile)

    {
        final Document layoutDoc = super.getPersonalUserLayout(person, profile);
        final Element layout = layoutDoc.getDocumentElement();
        layout.setAttribute(Constants.NS_DECL, Constants.NS_URI);
        return layoutDoc;
    }

    /**
     * Returns the layout for a user. This method overrides the same
     * method in the superclass to return a composite layout for non
     * fragment owners and a regular layout for layout owners. A
     * composite layout is made up of layout pieces from potentially
     * multiple incorporated layouts. If no layouts are defined then
     * the composite layout will be the same as the user's personal
     * layout fragment or PLF, the one holding only those UI elements
     * that they own or incorporated elements that they have been
     * allowed to changed.
     **/
    private DistributedUserLayout _getUserLayout(IPerson person, IUserProfile profile)

    {
        final String userName = (String) person.getAttribute("username");
        final FragmentDefinition ownedFragment = this.getOwnedFragment(person);
        final boolean isLayoutOwnerDefault = this.isLayoutOwnerDefault(person);

        // if this user is an owner then ownedFragment will be non null. For
        // fragment owners and owners of any default layout from which a
        // fragment owners layout is copied there should not be any imported
        // distributed layouts. Instead, load their plf, mark as an owned
        // if a fragment owner, and return.

        if (ownedFragment != null || isLayoutOwnerDefault) {
            Document PLF, ILF = null;
            PLF = this._safeGetUserLayout(person, profile);
            ILF = (Document) PLF.cloneNode(true);

            final Element layoutNode = ILF.getDocumentElement();

            if (ownedFragment != null) {
                layoutNode.setAttributeNS(Constants.NS_URI, Constants.ATT_FRAGMENT_NAME, ownedFragment.getName());
                if (LOG.isDebugEnabled()) {
                    LOG.debug("User '" + userName + "' is owner of '" + ownedFragment.getName() + "' fragment.");
                }
            }
            else if (isLayoutOwnerDefault) {
                layoutNode.setAttributeNS(Constants.NS_URI, Constants.ATT_IS_TEMPLATE_USER, "true");
                layoutNode.setAttributeNS(Constants.NS_URI,
                        Constants.ATT_TEMPLATE_LOGIN_ID,
                        (String) person.getAttribute("username"));
            }
            // cache in person as PLF for storage later like normal users
            person.setAttribute(Constants.PLF, PLF);
            return new DistributedUserLayout(ILF);
        }

        return this.getCompositeLayout(person, profile);
    }

    /**
     * Convenience method for fragment activator to obtain raw layouts for
     * fragments during initialization.
     */
    public Document getFragmentLayout(IPerson person, IUserProfile profile)

    {
        return this._safeGetUserLayout(person, profile);
    }

    /**
     * Generates a new struct id for directive elements that dlm places in
     * the PLF version of the layout tree. These elements are atifacts of the
     * dlm storage model and used during merge but do not appear in the user's
     * composite view.
     */
    @Override
    public String getNextStructDirectiveId(IPerson person) {
        return super.getNextStructId(person, Constants.DIRECTIVE_PREFIX);
    }

    /**
       Replaces the layout Document stored on a fragment definition with a new
       version. This is called when a fragment owner updates their layout.
     */
    private void updateCachedLayout(Document layout, IUserProfile profile, FragmentDefinition fragment) {
        // need to make a copy that we can fragmentize
        layout = (Document) layout.cloneNode(true);

        final FragmentActivator activator = this.getFragmentActivator();

        // Fix later to handle multiple profiles
        final Element root = layout.getDocumentElement();
        final UserView userView = activator.getUserView(fragment);
        if (userView == null) {
            throw new IllegalStateException("No UserView found for fragment: " + fragment.getName());
        }

        root.setAttribute(Constants.ATT_ID, Constants.FRAGMENT_ID_USER_PREFIX + userView.getUserId()
                + Constants.FRAGMENT_ID_LAYOUT_PREFIX + "1");
        final UserView view = new UserView(userView.getUserId(), profile, layout);
        try {
            activator.fragmentizeLayout(view, fragment);
            activator.setUserView(fragment.getOwnerId(), view);
        }
        catch (final Exception e) {
            LOG.error("An exception occurred attempting to update a layout.", e);
        }
    }

    /**
       Returns true is the user is the owner of a layout which is copied as the
       default for any fragment when first created.
    */
    private boolean isLayoutOwnerDefault(IPerson person) {
        final String userName = (String) person.getAttribute("username");

        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();
        if (userName != null && definitions != null) {
            for (final FragmentDefinition fragmentDefinition : definitions) {
                if (fragmentDefinition.defaultLayoutOwnerID != null
                        && fragmentDefinition.defaultLayoutOwnerID.equals(userName)) {
                    return true;
                }
            }
        }
        final String globalDefault = PropertiesManager.getProperty("org.jasig.portal.layout.dlm.defaultLayoutOwner");
        if (globalDefault != null && globalDefault.equals(userName)) {
            return true;
        }

        if (!this.systemDefaultUserLoaded) {
            this.systemDefaultUserLoaded = true;
            try {
                this.systemDefaultUser = PropertiesManager.getProperty(TEMPLATE_USER_NAME);
            }
            catch (final RuntimeException re) {
                LOG.error("Property '" + TEMPLATE_USER_NAME + "' not defined.", re);
            }
            if (this.systemDefaultUser != null && this.systemDefaultUser.equals(userName)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isFragmentOwner(IPerson person) {
        return this.getOwnedFragment(person) != null;
    }

    @Override
    public boolean isFragmentOwner(String username) {

        boolean rslt = false; // default

        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();
        if (definitions != null) {
            for (final FragmentDefinition fragmentDefinition : definitions) {
                if (fragmentDefinition.getOwnerId().equals(username)) {
                    rslt = true;
                    break;
                }
            }
        }

        return rslt;

    }

    /**
       Returns the fragment owned by this user if any. If this user is not a
       fragment owner then null is returned.
    */
    private FragmentDefinition getOwnedFragment(IPerson person) {
        final String userName = person.getUserName();

        final FragmentActivator activator = this.getFragmentActivator();

        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();
        if (definitions != null) {
            for (final FragmentDefinition fragmentDefinition : definitions) {
                if (userName.equals(fragmentDefinition.getOwnerId())) {
                    return fragmentDefinition;
                }
            }
        }
        return null;
    }

    /**
    This method passed through the set of ordered fragments asking each one if
    it is applicable to this user. If so then it is included in a list of
    applicable layout fragments. These are then combined into an ILF,
    incorporated layouts fragment, and finally the user's PLF, personal layout
    fragment, is merged in and the composite layout returned.
    */
    private DistributedUserLayout getCompositeLayout(IPerson person, IUserProfile profile)

    {
        final Set<String> fragmentNames = new LinkedHashSet<String>();
        final List<Document> applicables = new LinkedList<Document>();

        final List<FragmentDefinition> definitions = this.configurationLoader.getFragments();

        if (this.log.isDebugEnabled()) {
            this.log.debug("About to check applicability of " + definitions.size() + " fragments");
        }

        final FragmentActivator activator = this.getFragmentActivator();

        if (definitions != null) {
            for (final FragmentDefinition fragmentDefinition : definitions) {

                if (this.log.isDebugEnabled()) {
                    this.log.debug("Checking applicability of the following fragment:  " + fragmentDefinition.getName());
                }

                if (fragmentDefinition.isApplicable(person)) {
                    final UserView userView = activator.getUserView(fragmentDefinition);
                    if (userView != null) {
                        applicables.add(userView.layout);
                    }
                    fragmentNames.add(fragmentDefinition.getName());
                }
            }
        }

        Document PLF = (Document) person.getAttribute(Constants.PLF);

        if (null == PLF) {
            PLF = this._safeGetUserLayout(person, profile);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("PLF for " + person.getAttribute(IPerson.USERNAME) + " immediately after loading\n"
                    + XmlUtilitiesImpl.toString(PLF));
        }

        final Document ILF = ILFBuilder.constructILF(PLF, applicables, person);
        person.setAttribute(Constants.PLF, PLF);
        final IntegrationResult result = new IntegrationResult();
        PLFIntegrator.mergePLFintoILF(PLF, ILF, result);
        if (LOG.isDebugEnabled()) {
            LOG.debug("PLF for " + person.getAttribute(IPerson.USERNAME) + " after MERGING\n"
                    + XmlUtilitiesImpl.toString(PLF));
            LOG.debug("ILF for " + person.getAttribute(IPerson.USERNAME) + " after MERGING\n"
                    + XmlUtilitiesImpl.toString(ILF));
        }
        // push optimizations made during merge back into db.
        if (result.changedPLF) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Saving PLF for " + person.getAttribute(IPerson.USERNAME) + " due to changes during merge.");
            }
            super.setUserLayout(person, profile, PLF, false);
        }

        final int structureStylesheetId = profile.getStructureStylesheetId();
        final IStylesheetUserPreferences distributedStructureStylesheetUserPreferences = this
                .loadDistributedStylesheetUserPreferences(person, profile, structureStylesheetId, fragmentNames);

        final int themeStylesheetId = profile.getThemeStylesheetId();
        final IStylesheetUserPreferences distributedThemeStylesheetUserPreferences = this
                .loadDistributedStylesheetUserPreferences(person, profile, themeStylesheetId, fragmentNames);

        return new DistributedUserLayout(ILF, fragmentNames, distributedStructureStylesheetUserPreferences,
                distributedThemeStylesheetUserPreferences);
    }

    /**
       This method overrides the same method in the super class to persist
       only layout information stored in the user's person layout fragment
       or PLF. If this person is a layout owner then their changes are pushed
       into the appropriate layout fragment.
     */
    public void setUserLayout(IPerson person, IUserProfile profile, Document layoutXML, boolean channelsAdded)

    {
        this.setUserLayout(person, profile, layoutXML, channelsAdded, true);
    }

    /**
       This method overrides the same method in the super class to persist
       only layout information stored in the user's person layout fragment
       or PLF. If fragment cache update is requested then it checks to see if
       this person is a layout owner and if so then their changes are pushed
       into the appropriate layout fragment.
     */
    @Override
    public void setUserLayout(IPerson person, IUserProfile profile, Document layoutXML, boolean channelsAdded,
            boolean updateFragmentCache)

    {
        final Document plf = (Document) person.getAttribute(Constants.PLF);
        if (LOG.isDebugEnabled()) {
            LOG.debug("PLF for " + person.getAttribute(IPerson.USERNAME) + "\n" + XmlUtilitiesImpl.toString(plf));
        }
        super.setUserLayout(person, profile, plf, channelsAdded);

        if (updateFragmentCache) {
            final FragmentDefinition fragment = this.getOwnedFragment(person);

            if (fragment != null) {
                this.updateCachedLayout(plf, profile, fragment);
            }
        }
    }

    @Override
    public FragmentChannelInfo getFragmentChannelInfo(String sId) {
        final FragmentNodeInfo node = this.getFragmentNodeInfo(sId);

        if (node != null && node instanceof FragmentChannelInfo) {
            return (FragmentChannelInfo) node;
        }
        return null;
    }

    @Override
    public FragmentNodeInfo getFragmentNodeInfo(String sId) {
        // grab local pointers to variables subject to change at any time
        final List<FragmentDefinition> fragments = this.configurationLoader.getFragments();

        final FragmentActivator activator = this.getFragmentActivator();

        final net.sf.ehcache.Element element = this.fragmentNodeInfoCache.get(sId);
        FragmentNodeInfo info = element != null ? (FragmentNodeInfo) element.getObjectValue() : null;

        if (info == null) {
            for (final FragmentDefinition fragmentDefinition : fragments) {
                final UserView userView = activator.getUserView(fragmentDefinition);
                if (userView == null) {
                    this.log.warn("No UserView is present for fragment " + fragmentDefinition.getName()
                            + " it will be skipped when fragment node information");
                    continue;
                }

                final Element node = userView.layout.getElementById(sId);
                if (node != null) // found it
                {
                    if (node.getTagName().equals(Constants.ELM_CHANNEL)) {
                        info = new FragmentChannelInfo(node);
                    }
                    else {
                        info = new FragmentNodeInfo(node);
                    }
                    this.fragmentNodeInfoCache.put(new net.sf.ehcache.Element(sId, info));
                    break;
                }
            }
        }
        return info;
    }

    /**
       When user preferences are stored in the database for changes made to
       an incorporated node the node id can not be used because it does not
       represent a row in the up_layout_struct table for the user. The plfid
       must be used. Null will never be returned unless the layout or
       processing has really been screwed up. This is because changes made to
       the user prefs calls UserPrefsHandler which generates a shadow node in
       the db and sets the plfid of that node into the corresponding node in
       the PLF prior to the call to update the user prefs in the db.
     */
    private String getPlfId(Document PLF, String incdId) {
        Element element = null;
        try {
            final XPathFactory fac = XPathFactory.newInstance();
            final XPath xp = fac.newXPath();
            element = (Element) xp.evaluate("//*[@ID = '" + incdId + "']", PLF, XPathConstants.NODE);
        }
        catch (final XPathExpressionException xpee) {
            throw new RuntimeException(xpee);
        }
        if (element == null) {
            this.log.warn("The specified folderId was not found in the user's PLF:  " + incdId);
            return null;
        }
        final Attr attr = element.getAttributeNode(Constants.ATT_PLF_ID);
        if (attr == null) {
            return null;
        }
        return attr.getValue();
    }

    @Override
    protected Element getStructure(Document doc, LayoutStructure ls) {
        Element structure = null;

        // handle migration of legacy namespace
        String type = ls.getType();
        if (type != null && type.startsWith(Constants.LEGACY_NS)) {
            type = Constants.NS + type.substring(Constants.LEGACY_NS.length());
        }

        if (ls.isChannel()) {
            final IPortletDefinition channelDef = this.portletDefinitionRegistry.getPortletDefinition(String.valueOf(ls
                    .getChanId()));
            if (channelDef != null && channelApproved(channelDef.getApprovalDate())) {
                structure = this
                        .getElementForChannel(doc, channelPrefix + ls.getStructId(), channelDef, ls.getLocale());
            }
            else {
                // Create an error channel if channel is missing or not approved
                String missingChannel = "Unknown";
                if (channelDef != null) {
                    missingChannel = channelDef.getName();
                }
                structure = this.getElementForChannel(doc,
                        channelPrefix + ls.getStructId(),
                        MissingPortletDefinition.INSTANCE,
                        null);
                //        structure = MissingPortletDefinition.INSTANCE.getDocument(doc, channelPrefix + ls.getStructId());
                //        structure = MissingPortletDefinition.INSTANCE.getDocument(doc, channelPrefix + ls.getStructId(),
                //                "The '" + missingChannel + "' channel is no longer available. " +
                //                "Please remove it from your layout.",
                //                -1);
            }
        }
        else {
            // create folder objects including dlm new types in cp namespace
            if (type != null && type.startsWith(Constants.NS)) {
                structure = doc.createElementNS(Constants.NS_URI, type);
            }
            else {
                structure = doc.createElement("folder");
            }
            structure.setAttribute("name", ls.getName());
            structure.setAttribute("type", (type != null ? type : "regular"));
        }

        structure.setAttribute("hidden", (ls.isHidden() ? "true" : "false"));
        structure.setAttribute("immutable", (ls.isImmutable() ? "true" : "false"));
        structure.setAttribute("unremovable", (ls.isUnremovable() ? "true" : "false"));
        if (localeAware) {
            structure.setAttribute("locale", ls.getLocale()); // for i18n by Shoji
        }

        /*
         * Parameters from up_layout_param are loaded slightly differently for
         * folders and channels. For folders all parameters are added as attributes
         * of the Element. For channels only those parameters with names starting
         * with the dlm namespace Constants.NS are added as attributes to the Element.
         * Others are added as child parameter Elements.
         */
        if (ls.getParameters() != null) {
            for (final Iterator itr = ls.getParameters().iterator(); itr.hasNext();) {
                final StructureParameter sp = (StructureParameter) itr.next();
                String pName = sp.getName();

                // handle migration of legacy namespace
                if (pName.startsWith(Constants.LEGACY_NS)) {
                    pName = Constants.NS + sp.getName().substring(Constants.LEGACY_NS.length());
                }

                if (!ls.isChannel()) { // Folder
                    if (pName.startsWith(Constants.NS)) {
                        structure.setAttributeNS(Constants.NS_URI, pName, sp.getValue());
                    }
                    else {
                        structure.setAttribute(pName, sp.getValue());
                    }
                }
                else // Channel
                {
                    // if dealing with a dlm namespace param add as attribute
                    if (pName.startsWith(Constants.NS)) {
                        structure.setAttributeNS(Constants.NS_URI, pName, sp.getValue());
                        itr.remove();
                    }
                    else {
                        /*
                         * do traditional override processing. some explanation is in
                         * order. The structure element was created by the
                         * ChannelDefinition and only contains parameter children if the
                         * definition had defined parameters. These are checked for each
                         * layout loaded parameter as found in LayoutStructure.parameters.
                         * If a name match is found then we need to see if overriding is
                         * allowed and if so we set the value on the child parameter
                         * element. At that point we are done with that version loaded
                         * from the layout so we remove it from the in-memory set of
                         * parameters that are being merged-in. Then, after all such have
                         * been checked against those added by the channel definition we
                         * add in any remaining as adhoc, unregulated parameters.
                         */
                        final NodeList nodeListParameters = structure.getElementsByTagName("parameter");
                        for (int j = 0; j < nodeListParameters.getLength(); j++) {
                            final Element parmElement = (Element) nodeListParameters.item(j);
                            final NamedNodeMap nm = parmElement.getAttributes();

                            final String nodeName = nm.getNamedItem("name").getNodeValue();
                            if (nodeName.equals(pName)) {
                                final Node override = nm.getNamedItem("override");
                                if (override != null && override.getNodeValue().equals("yes")) {
                                    final Node valueNode = nm.getNamedItem("value");
                                    valueNode.setNodeValue(sp.getValue());
                                }
                                itr.remove();
                                break; // found the corresponding one so skip the rest
                            }
                        }
                    }
                }
            }
            // For channels, add any remaining parameter elements loaded with the
            // layout as adhoc, unregulated, parameter children that can be overridden.
            if (ls.isChannel()) {
                for (final Iterator itr = ls.getParameters().iterator(); itr.hasNext();) {
                    final StructureParameter sp = (StructureParameter) itr.next();
                    final Element parameter = doc.createElement("parameter");
                    parameter.setAttribute("name", sp.getName());
                    parameter.setAttribute("value", sp.getValue());
                    parameter.setAttribute("override", "yes");
                    structure.appendChild(parameter);
                }
            }
        }
        // finish setting up elements based on loaded params
        final String origin = structure.getAttribute(Constants.ATT_ORIGIN);
        final String prefix = ls.isChannel() ? channelPrefix : folderPrefix;

        // if not null we are dealing with a node incorporated from another
        // layout and this node contains changes made by the user so handle
        // id swapping.
        if (!origin.equals("")) {
            structure.setAttributeNS(Constants.NS_URI, Constants.ATT_PLF_ID, prefix + ls.getStructId());
            structure.setAttribute("ID", origin);
        }
        else if (!ls.isChannel())
        // regular folder owned by this user, need to check if this is a
        // directive or ui element. If the latter then use traditional id
        // structure
        {
            if (type != null && type.startsWith(Constants.NS)) {
                structure.setAttribute("ID", Constants.DIRECTIVE_PREFIX + ls.getStructId());
            }
            else {
                structure.setAttribute("ID", folderPrefix + ls.getStructId());
            }
        }
        else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Adding identifier " + folderPrefix + ls.getStructId());
            }
            structure.setAttribute("ID", channelPrefix + ls.getStructId());
        }
        structure.setIdAttribute(Constants.ATT_ID, true);
        return structure;
    }

    @Override
    protected int saveStructure(Node node, PreparedStatement structStmt, PreparedStatement parmStmt)
            throws SQLException {
        if (node == null) { // No more
            return 0;
        }
        if (node.getNodeName().equals("parameter")) {
            //parameter, skip it and go on to the next node
            return this.saveStructure(node.getNextSibling(), structStmt, parmStmt);
        }
        if (!(node instanceof Element)) {
            return 0;
        }

        final Element structure = (Element) node;

        if (LOG.isDebugEnabled()) {
            LOG.debug("saveStructure XML content: " + XmlUtilitiesImpl.toString(node));
        }

        // determine the struct_id for storing in the db. For incorporated nodes in
        // the plf their ID is a system-wide unique ID while their struct_id for
        // storing in the db is cached in a dlm:plfID attribute.
        int saveStructId = -1;
        final String plfID = structure.getAttribute(Constants.ATT_PLF_ID);

        if (!plfID.equals("")) {
            saveStructId = Integer.parseInt(plfID.substring(1));
        }
        else {
            final String id = structure.getAttribute("ID");
            saveStructId = Integer.parseInt(id.substring(1));
        }

        int nextStructId = 0;
        int childStructId = 0;
        int chanId = -1;
        final boolean isChannel = node.getNodeName().equals("channel");

        if (node.hasChildNodes()) {
            childStructId = this.saveStructure(node.getFirstChild(), structStmt, parmStmt);
        }
        nextStructId = this.saveStructure(node.getNextSibling(), structStmt, parmStmt);
        structStmt.clearParameters();
        structStmt.setInt(1, saveStructId);
        structStmt.setInt(2, nextStructId);
        structStmt.setInt(3, childStructId);

        final String externalId = structure.getAttribute("external_id");
        if (externalId != null && externalId.trim().length() > 0) {
            final Integer eID = new Integer(externalId);
            structStmt.setInt(4, eID.intValue());
        }
        else {
            structStmt.setNull(4, java.sql.Types.NUMERIC);

        }
        if (isChannel) {
            chanId = Integer.parseInt(node.getAttributes().getNamedItem("chanID").getNodeValue());
            structStmt.setInt(5, chanId);
            structStmt.setNull(6, java.sql.Types.VARCHAR);
        }
        else {
            structStmt.setNull(5, java.sql.Types.NUMERIC);
            structStmt.setString(6, structure.getAttribute("name"));
        }
        final String structType = structure.getAttribute("type");
        structStmt.setString(7, structType);
        structStmt.setString(8, RDBMServices.dbFlag(xmlBool(structure.getAttribute("hidden"))));
        structStmt.setString(9, RDBMServices.dbFlag(xmlBool(structure.getAttribute("immutable"))));
        structStmt.setString(10, RDBMServices.dbFlag(xmlBool(structure.getAttribute("unremovable"))));
        if (LOG.isDebugEnabled()) {
            LOG.debug(structStmt.toString());
        }
        structStmt.executeUpdate();

        // code to persist extension attributes for dlm
        final NamedNodeMap attribs = node.getAttributes();
        for (int i = 0; i < attribs.getLength(); i++) {
            final Node attrib = attribs.item(i);
            final String name = attrib.getNodeName();

            if (name.startsWith(Constants.NS) && !name.equals(Constants.ATT_PLF_ID)
                    && !name.equals(Constants.ATT_FRAGMENT) && !name.equals(Constants.ATT_PRECEDENCE)) {
                // a cp extension attribute. Push into param table.
                parmStmt.clearParameters();
                parmStmt.setInt(1, saveStructId);
                parmStmt.setString(2, name);
                parmStmt.setString(3, attrib.getNodeValue());
                if (LOG.isDebugEnabled()) {
                    LOG.debug(parmStmt.toString());
                }
                parmStmt.executeUpdate();
            }
        }
        final NodeList parameters = node.getChildNodes();
        if (parameters != null && isChannel) {
            final IPortletDefinition channelDef = this.portletDefinitionRegistry.getPortletDefinition(String
                    .valueOf(chanId));
            for (int i = 0; i < parameters.getLength(); i++) {
                if (parameters.item(i).getNodeName().equals("parameter")) {
                    final Element parmElement = (Element) parameters.item(i);
                    final NamedNodeMap nm = parmElement.getAttributes();
                    final String parmName = nm.getNamedItem("name").getNodeValue();
                    final String parmValue = nm.getNamedItem("value").getNodeValue();
                    final Node override = nm.getNamedItem("override");

                    // if no override specified then default to allowed
                    if (override != null && !override.getNodeValue().equals("yes")) {
                        // can't override
                    }
                    else {
                        // override only for adhoc or if diff from chan def
                        final IPortletDefinitionParameter cp = channelDef.getParameter(parmName);
                        if (cp == null || !cp.getValue().equals(parmValue)) {
                            parmStmt.clearParameters();
                            parmStmt.setInt(1, saveStructId);
                            parmStmt.setString(2, parmName);
                            parmStmt.setString(3, parmValue);
                            if (LOG.isDebugEnabled()) {
                                LOG.debug(parmStmt);
                            }
                            parmStmt.executeUpdate();
                        }
                    }
                }
            }
        }
        return saveStructId;
    }

    public static Document getPLF(IPerson person) throws PortalException {
        try {
            return (Document) person.getAttribute(Constants.PLF);
        }
        catch (final Exception ex) {
            throw new PortalException(ex);
        }
    }

    private Element getElementForChannel(Document doc, String chanId, IPortletDefinition def, String locale) {
        final Element channel = doc.createElement("channel");

        // the ID attribute is the identifier for the Channel element
        channel.setAttribute("ID", chanId);
        channel.setIdAttribute("ID", true);

        channel.setAttribute("chanID", def.getPortletDefinitionId().getStringId());
        channel.setAttribute("timeout", String.valueOf(def.getTimeout()));
        if (locale != null) {
            channel.setAttribute("name", def.getName(locale));
            channel.setAttribute("title", def.getTitle(locale));
            channel.setAttribute("description", def.getDescription(locale));
            channel.setAttribute("locale", locale);
        }
        else {
            channel.setAttribute("name", def.getName());
            channel.setAttribute("title", def.getTitle());
            channel.setAttribute("description", def.getDescription());
        }
        channel.setAttribute("fname", def.getFName());

        // chanClassArg is so named to highlight that we are using the argument
        // to the method rather than the instance variable chanClass
        channel.setAttribute("typeID", String.valueOf(def.getType().getId()));

        for (final IPortletDefinitionParameter param : def.getParameters()) {
            final Element parameter = doc.createElement("parameter");
            parameter.setAttribute("name", param.getName());
            parameter.setAttribute("value", param.getValue());
            channel.appendChild(parameter);
        }

        return channel;

    }

    private static final class MissingPortletDefinition implements IPortletDefinition {
        public static final IPortletDefinition INSTANCE = new MissingPortletDefinition();

        private final String fname = "DLMStaticMissingChannel";

        public String getName() {
            return "Missing channel";
        }

        public String getName(String locale) {
            return "Missing channel";
        }

        public int getTimeout() {
            return 20000;
        }

        public String getTitle() {
            return "Missing channel";
        }

        public String getTitle(String locale) {
            return "Missing channel";
        }

        public String getFName() {
            return this.fname;
        }

        @Override
        public String getDataId() {
            return null;
        }

        @Override
        public String getDataTitle() {
            return this.getName();
        }

        @Override
        public String getDataDescription() {
            return this.getDescription();
        }

        @Override
        public Integer getActionTimeout() {
            return null;
        }

        @Override
        public Integer getEventTimeout() {
            return null;
        }

        @Override
        public Integer getRenderTimeout() {
            return null;
        }

        @Override
        public Integer getResourceTimeout() {
            return null;
        }

        @Override
        public void setActionTimeout(Integer actionTimeout) {
        }

        @Override
        public void setEventTimeout(Integer eventTimeout) {
        }

        @Override
        public void setRenderTimeout(Integer renderTimeout) {
        }

        @Override
        public void setResourceTimeout(Integer resourceTimeout) {
        }

        public void addLocalizedDescription(String locale, String chanDesc) {
        }

        public void addLocalizedName(String locale, String chanName) {
        }

        public void addLocalizedTitle(String locale, String chanTitle) {
        }

        public void addParameter(IPortletDefinitionParameter parameter) {
        }

        public void clearParameters() {
        }

        public Date getApprovalDate() {
            return null;
        }

        public int getApproverId() {
            return 0;
        }

        public String getDescription() {
            return null;
        }

        public String getDescription(String locale) {
            return null;
        }

        public EntityIdentifier getEntityIdentifier() {
            return null;
        }

        public Date getExpirationDate() {
            return null;
        }

        public int getExpirerId() {
            return 0;
        }

        public IPortletDefinitionParameter getParameter(String key) {
            return null;
        }

        public Set<IPortletDefinitionParameter> getParameters() {
            return null;
        }

        public Map<String, IPortletDefinitionParameter> getParametersAsUnmodifiableMap() {
            return null;
        }

        public Date getPublishDate() {
            return null;
        }

        public int getPublisherId() {
            return 0;
        }

        public boolean hasAbout() {
            return false;
        }

        public boolean hasHelp() {
            return false;
        }

        public boolean isEditable() {
            return false;
        }

        public void removeParameter(IPortletDefinitionParameter parameter) {
        }

        public void removeParameter(String name) {
        }

        public void replaceParameters(Set<IPortletDefinitionParameter> parameters) {
        }

        public void setApprovalDate(Date approvalDate) {
        }

        public void setApproverId(int approvalId) {
        }

        public void setDescription(String descr) {
        }

        public void setEditable(boolean editable) {
        }

        public void setExpirationDate(Date expirationDate) {
        }

        public void setExpirerId(int expirerId) {
        }

        public void setFName(String fname) {
        }

        public void setHasAbout(boolean hasAbout) {
        }

        public void setHasHelp(boolean hasHelp) {
        }

        public void setName(String name) {
        }

        public void setParameters(Set<IPortletDefinitionParameter> parameters) {
        }

        public void setPublishDate(Date publishDate) {
        }

        public void setPublisherId(int publisherId) {
        }

        public void setTimeout(int timeout) {
        }

        public void setTitle(String title) {
        }

        public IPortletType getType() {
            return new MissingPortletType();
        }

        public void setType(IPortletType channelType) {
        }

        public PortletLifecycleState getLifecycleState() {
            return null;
        }

        public IPortletDefinitionId getPortletDefinitionId() {
            return new MissingPortletDefinitionId();
        }

		@Override
		public List<IPortletPreference> getPortletPreferences() {
			return Collections.emptyList();
		}

		public void addParameter(String name, String value) {
        }

        @Override
        public void setPortletPreferences(List<IPortletPreference> portletPreferences) {

        }

        @Override
        public IPortletDescriptorKey getPortletDescriptorKey() {
            return null;
        }

        @Override
        public String toString() {
            return "MissingPortletDefinition [fname=" + this.fname + "]";
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (this.fname == null ? 0 : this.fname.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (!IPortletDefinition.class.isAssignableFrom(obj.getClass())) {
                return false;
            }
            final IPortletDefinition other = (IPortletDefinition) obj;
            if (this.fname == null) {
                if (other.getFName() != null) {
                    return false;
                }
            }
            else if (!this.fname.equals(other.getFName())) {
                return false;
            }
            return true;
        }
    }

    private static final class MissingPortletDefinitionId implements IPortletDefinitionId {

        public String getStringId() {
            return "-1";
        }

    }

    private static final class MissingPortletType implements IPortletType {

        public int getId() {
            // TODO Auto-generated method stub
            return -1;
        }

        public String getName() {
            // TODO Auto-generated method stub
            return null;
        }

        public String getDescription() {
            // TODO Auto-generated method stub
            return null;
        }

        public String getCpdUri() {
            // TODO Auto-generated method stub
            return null;
        }

        public void setDescription(String descr) {
            // TODO Auto-generated method stub

        }

        @Override
        public void setCpdUri(String cpdUri) {
            // TODO Auto-generated method stub

        }

        @Override
        public String getDataId() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public String getDataTitle() {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public String getDataDescription() {
            // TODO Auto-generated method stub
            return null;
        }

    }

}