/* 
 * Copyright 2007 Luke Daley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gldapo
import gldapo.schema.GldapoContextMapper
import gldapo.schema.GldapoSchemaRegistration
import gldapo.exception.GldapoException
import gldapo.exception.GldapoInvalidConfigException
import gldapo.search.SearchControlProvider
import gldapo.search.SearchControls
import gldapo.search.SearchProvider
import org.springframework.ldap.core.LdapTemplate
import org.springframework.ldap.core.support.LdapContextSource
import org.springframework.ldap.core.ContextMapperCallbackHandler
import org.springframework.ldap.control.PagedResultsRequestControl
import org.springframework.ldap.LimitExceededException
import org.springframework.ldap.core.ContextMapper
import org.springframework.ldap.NamingException
import javax.naming.directory.ModificationItem
import javax.naming.directory.Attributes
import org.springframework.ldap.core.DistinguishedName
import org.springframework.ldap.pool.factory.PoolingContextSource
import org.springframework.ldap.pool.validation.DefaultDirContextValidator

class GldapoDirectory implements SearchProvider {
    
    /**
     * The key that the config for the directories search controls should be under ({@value})
     * 
     * @see #GldapoDirectory(String,Map)
     */
    static final CONFIG_SEARCH_CONTROLS_KEY = 'searchControls'
    
    /**
     * The attributes in the config that are for the underlying context source of the underlying template.
     * <p>
     * The properties and their meanings are ...
     * <ul>
     * <li>url - A string indicating the location of the directory (e.g. {@code "ldap://example.com"})
     * <li>urls - A list of url strings
     * <li>base - The base dn for operations for this directory (e.g. {@code "dc=example,dc=com"})
     * <li>userDn - The distinguished name of the user to bind to the directory as
     * <li>password - The password of the user to bind as
     * </ul>
     */
    static final CONTEXT_SOURCE_PROPS = ["url", "urls", "base", "userDn", "password"]
    static final POOLING_SOURCE_PROPS = ["maxActive", "maxIdle", "minIdle", "maxTotal", "timeBetweenEvictionRunsMillis", "minEvictableIdleTimeMillis", "whenExhaustedAction", "testWhileIdle", "testOnReturn"]
    
    /**
     * 
     */
    SearchControlProvider searchControls
    
    /**
     * 
     */
    String name
    
    /**
     * 
     */
    def template
    
    /**
     * Creates a new instance via a config map. 
     * <p>
     * The config map contains the attributes for the underlying {@link LdapContextSource} 
     * (see {@link #CONTEXT_SOURCE_PROPS}). The only mandatory attribute is {@code url} 
     * (or {@code urls}). The rest are optional.
     * <p>
     * The config map can also contain a map under the key {@code env} which will be used as the environment
     * properties for the connection (e.g java.naming.ldap.attributes.binary)
     * <p>
     * It can also contain a map under the key denoted by {@link #CONFIG_SEARCH_CONTROLS_KEY} 
     * that is used to construct an instance of {@link SearchControls} using the 
     * {@link SearchControls#SearchControls(Map)} constructor. If it is omitted, a search controls
     * object with default values is used.
     * <p>
     * 
     */
    GldapoDirectory(String name, Map config) {
        if (config == null) throw new GldapoInvalidConfigException("Config for directory '$name' is null" as String)
        this.name = name
        def contextSource = new LdapContextSource()
        CONTEXT_SOURCE_PROPS.each {
            if (config.containsKey(it)) { 
                contextSource."$it" = config."$it"
            }
        }
        if (config.containsKey("env")) {
            contextSource.baseEnvironmentProperties = config.env
        }
        contextSource.afterPropertiesSet()

        if(config.containsKey("pooling")) {
          def realSource = contextSource
          realSource.pooled = false
          contextSource = new PoolingContextSource()
          contextSource.contextSource = realSource
          if (config.pooling.validate) {
            def validator = new DefaultDirContextValidator()
            contextSource.dirContextValidator = validator
            contextSource.testOnBorrow = true
          }
          POOLING_SOURCE_PROPS.each {
            if(config.pooling.containsKey(it)) {
              contextSource."$it" = config.pooling."$it"
            }
          }
        }
        
        this.template = new LdapTemplate(contextSource: contextSource)
        this.template.afterPropertiesSet()
        
        if (config.containsKey("ignorePartialResultException")) 
            this.template.ignorePartialResultException = config.ignorePartialResultException
            
        this.searchControls = new SearchControls(config[CONFIG_SEARCH_CONTROLS_KEY])
    }
    
    /**
     * Returns the base DN for operations for this directory
     */
    DistinguishedName getBase() {
      def contextSource = template?.contextSource
      if (contextSource?.class == PoolingContextSource.class) {
        return contextSource?.contextSource?.base
      }
      return contextSource?.base
    }
    
    /**
     * Performs a search operation on the directory.
     * <p>
     * If the search control provider contains a {@code pageSize} that is greater than 1
     * {@link #pagedSearch(DistinguishedName,String,SearchControls,ContextMapperCallbackHandler,Integer) pagedSearch()} is used,
     * otherwise {@link #nonPagedSearch(DistinguishedName,String,SearchControls,ContextMapperCallbackHandler) nonPagedSearch()} is used.
     * <p>
     * An instance of {@link SearchControls} is made out of the gldapo search controls ({@code controls}) to be used by
     * the {@link LdapTemplate} instance which does the actual searching.
     * 
     * @param schemaRegistration Provides the metadata about the target class in order to make objects (must be instance of GldapoSchemaRegistration)
     * @param base The base of the search operation, relative to the base of the directory
     * @param filter The LDAP filter string to use to restrict the search
     * @param controls Provides several settings that augment the search
     * @return A list of objects of the class that the schemaRegistration is for
     * @throws NamingException If any LDAP related error occurs
     */
    List search(Object schemaRegistration, DistinguishedName base, String filter, SearchControlProvider controls) throws NamingException {
        
        if (schemaRegistration instanceof GldapoSchemaRegistration == false) {
            throw new IllegalArgumentException("schemaRegistration must be an instance of GldapoSchemaRegistration")
        }
        
        ContextMapper mapper = new GldapoContextMapper(schemaRegistration: schemaRegistration, directory: this)
        ContextMapperCallbackHandler handler = new ContextMapperCallbackHandler(mapper)

        javax.naming.directory.SearchControls jndiControls = controls as javax.naming.directory.SearchControls
        jndiControls.returningAttributes = schemaRegistration.attributeMappings*.value.attributeName
        
        if (controls.pageSize == null || controls.pageSize < 1) {
            return nonPagedSearch(base, filter, jndiControls, handler)
        } else { 
            return pagedSearch(base, filter, jndiControls, handler, controls.pageSize)
        }
    }

    /**
     * Performs a search operation on the directory not using paging.
     * 
     * @param base The base of the search operation, relative to the base of the directory
     * @param filter The LDAP filter string to use to restrict the search
     * @param jndiControls Provides several settings that augment the search
     * @param handler responsible for processing the raw search results
     * @return A list of objects of the class that the schemaRegistration is for
     * @throws NamingException If any LDAP related error occurs
     */
    private List nonPagedSearch(DistinguishedName base, String filter, javax.naming.directory.SearchControls jndiControls, ContextMapperCallbackHandler handler) throws NamingException {
        try {
            this.template.search(base, filter, jndiControls, handler)
        } catch (LimitExceededException e) {
            // If the number of entries has hit the specified count limit OR
            // The server is unwilling to send more entries we will get here.
            // It's not really an error condition hence we just return what we found.
        }
        return handler.list       
    }

    /**
     * Performs a search operation on the directory not using paging.
     * 
     * @param base The base of the search operation, relative to the base of the directory
     * @param filter The LDAP filter string to use to restrict the search
     * @param jndiControls Provides several settings that augment the search
     * @param handler responsible for processing the raw search results
     * @param pageSize The number of entries to be returned in one page from the LDAP directory
     * @return A list of objects of the class that the schemaRegistration is for
     * @throws NamingException If any LDAP related error occurs
     */
    private List pagedSearch(DistinguishedName base, String filter, javax.naming.directory.SearchControls jndiControls, ContextMapperCallbackHandler handler, Integer pageSize) throws NamingException {
        try {
            PagedResultsRequestControl requestControl = new PagedResultsRequestControl(pageSize)
            this.template.search(base, filter, jndiControls, handler, requestControl)
        
            while (requestControl?.cookie?.cookie != null)
            {
                requestControl = new PagedResultsRequestControl(pageSize, requestControl.cookie)
                this.template.search(base, filter, jndiControls, handler, requestControl)
            } 
        } catch (LimitExceededException e) {
            // If the number of entries has hit the specified count limit OR
            // The server is unwilling to send more entries we will get here.
            // It's not really an error condition hence we just return what we found.
        }
        
        return handler.list
    }
    
    /**
     * 
     */
    void createEntry(DistinguishedName dn, Attributes attributes) {
        this.template.bind(dn, null, attributes)
    }
    
    /**
     * 
     */
    void updateEntry(DistinguishedName dn, List<ModificationItem> modificationItems) {
        this.template.modifyAttributes(dn, modificationItems as ModificationItem[])
    }
    
    /**
     * 
     */
    void moveEntry(DistinguishedName olddn, DistinguishedName newdn) {
        this.template.rename(olddn, newdn)
    }
    
    /**
     * 
     */
    void replaceEntry(DistinguishedName dn, Attributes attributes) {
        this.template.rebind(dn, null, attributes)
    }
    
    /**
     *
     */
     void deleteEntry(DistinguishedName dn) {
         this.template.unbind(dn)
     }
     
     /**
      * 
      */
     void deleteEntryRecursively(DistinguishedName dn) {
         this.template.unbind(dn, true)
     }
     
     def getSubContextSource(DistinguishedName brdn) {
        def context = template.contextSource
        def contextDnCopy = new DistinguishedName(context.baseLdapPath)
        def subContextDn = contextDnCopy.append(brdn)
        return context.class.newInstance(
            urls: context.urls,
            userDn: subContextDn as String,
            pooled: context.pooled
        )
     }
}