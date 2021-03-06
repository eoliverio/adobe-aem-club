package org.testsite.customlogin.authentication.impl;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.jcr.Credentials;
import javax.jcr.SimpleCredentials;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;

import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.AuthInfo;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.plugins.value.ValueFactoryImpl;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.authentication.AbstractLoginModule;
import org.apache.jackrabbit.oak.spi.security.authentication.AuthInfoImpl;
import org.apache.jackrabbit.oak.spi.security.authentication.ImpersonationCredentials;
import org.apache.jackrabbit.oak.spi.security.authentication.PreAuthenticatedLogin;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityException;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityProvider;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityProviderManager;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityRef;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalUser;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncContext;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncException;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncHandler;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncManager;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncedIdentity;
import org.apache.jackrabbit.oak.spi.whiteboard.Whiteboard;
import org.apache.jackrabbit.oak.spi.whiteboard.WhiteboardUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CustomExternalLoginModule implements a LoginModule that uses and external identity provider for login.
 */
public class CustomExternalLoginModule extends AbstractLoginModule {

    private static final Logger log = LoggerFactory.getLogger(CustomExternalLoginModule.class);

    // todo: make configurable
    private static final int MAX_SYNC_ATTEMPTS = 50;

    /**
     * Name of the parameter that configures the name of the external identity provider.
     */
    public static final String PARAM_IDP_NAME = "idp.name";

    /**
     * Name of the parameter that configures the name of the synchronization handler.
     */
    public static final String PARAM_SYNC_HANDLER_NAME = "sync.handlerName";

    /**
     * internal configuration when invoked from a factory rather than jaas
     */
    private ConfigurationParameters osgiConfig;

    /**
     * The external identity provider as specified by the {@link #PARAM_IDP_NAME}
     */
    private ExternalIdentityProvider idp;

    /**
     * The configured sync handler as specified by the {@link #PARAM_SYNC_HANDLER_NAME}
     */
    private SyncHandler syncHandler;

    /**
     * The external user as resolved in the login call.
     */
    private ExternalUser externalUser;

    /**
     * Login credentials
     */
    private Credentials credentials;

    /**
     * Default constructor for the OSGIi LoginModuleFactory case and the default non-OSGi JAAS case.
     */
    @SuppressWarnings("UnusedDeclaration")
    public CustomExternalLoginModule() {
    }

    /**
     * Creates a new ExternalLoginModule with the given OSGi config.
     * @param osgiConfig the config
     */
    public CustomExternalLoginModule(ConfigurationParameters osgiConfig) {
        this.osgiConfig = osgiConfig;
    }

    //--------------------------------------------------------< LoginModule >---
    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> ss, Map<String, ?> opts) {
        super.initialize(subject, callbackHandler, ss, opts);
        log.info("######## Inside Custom External Login Module Initialize Method #######");
        // merge options with osgi options if needed
        if (osgiConfig != null) {
            options = ConfigurationParameters.of(osgiConfig, options);
        }

        Whiteboard whiteboard = getWhiteboard();
        if (whiteboard == null) {
            log.error("External login module needs whiteboard. Will not be used for login.");
            return;
        }

        String idpName = options.getConfigValue(PARAM_IDP_NAME, "");
        if (idpName.length() == 0) {
            log.error("External login module needs IPD name. Will not be used for login.");
        } else {
            ExternalIdentityProviderManager idpMgr = WhiteboardUtils.getService(whiteboard, ExternalIdentityProviderManager.class);
            if (idpMgr == null) {
                log.error("External login module needs IDPManager. Will not be used for login.");
            } else {
                idp = idpMgr.getProvider(idpName);
                if (idp == null) {
                    log.error("No IDP found with name {}. Will not be used for login.", idpName);
                }
            }
        }

        String syncHandlerName = options.getConfigValue(PARAM_SYNC_HANDLER_NAME, "");
        if (syncHandlerName.length() == 0) {
            log.error("External login module needs SyncHandler name. Will not be used for login.");
        } else {
            SyncManager syncMgr = WhiteboardUtils.getService(whiteboard, SyncManager.class);
            if (syncMgr == null) {
                log.error("External login module needs SyncManager. Will not be used for login.");
            } else {
                syncHandler = syncMgr.getSyncHandler(syncHandlerName);
                if (syncHandler == null) {
                    log.error("No SyncHandler found with name {}. Will not be used for login.", syncHandlerName);
                }
            }
        }
    }

    @Override
    public boolean login() throws LoginException {
    	log.info("######## Inside Custom External Login Module Login Method #######");
        if (idp == null || syncHandler == null) {
        	log.info("######## Idp or SyncHandler null #######");
            return false;
        }
        credentials = getCredentials();

        // check if we have a pre authenticated login from a previous login module
        final String userId;
        final PreAuthenticatedLogin preAuthLogin = getSharedPreAuthLogin();
        if (preAuthLogin != null) {
            userId = preAuthLogin.getUserId();
        } else {
            userId = credentials instanceof SimpleCredentials ? ((SimpleCredentials) credentials).getUserID() : null;
        }
        if (userId == null && credentials == null) {
            log.debug("No credentials found for external login module. ignoring.");
            return false;
        }

        try {
            SyncedIdentity sId = null;
            UserManager userMgr = getUserManager();
            if (userId != null && userMgr != null) {
                sId = syncHandler.findIdentity(userMgr, userId);
                // if there exists an authorizable with the given userid but is
                // not an external one or if it belongs to another IDP, we just ignore it.
                if (sId != null) {
                    ExternalIdentityRef externalIdRef = sId.getExternalIdRef();
                    if (externalIdRef == null) {
                        log.debug("ignoring local user: {}", sId.getId());
                        return false;
                    } else if (!idp.getName().equals(externalIdRef.getProviderName())) {
                        if (log.isDebugEnabled()) {
                            log.debug("ignoring foreign identity: {} (idp={})", externalIdRef.getString(), idp.getName());
                        }
                        return false;
                    }
                }
            }

            if (preAuthLogin != null) {
            	log.info("######## Inside Custom External Login Module PreAuthLogin Method #######");
                externalUser = idp.getUser(preAuthLogin.getUserId());
            } else {
            	log.info("######## Inside Custom External Login Module Else PreAuthLogin Method #######");
                externalUser = idp.authenticate(credentials); // Authentication of LDAP user happens here
                log.info(" External User : " + externalUser);
            }

            if (externalUser != null) {
                log.debug("IDP {} returned valid user {}", idp.getName(), externalUser);

                if (credentials != null) {
                    //noinspection unchecked
                    sharedState.put(SHARED_KEY_CREDENTIALS, credentials);
                }

                //noinspection unchecked
                sharedState.put(SHARED_KEY_LOGIN_NAME, externalUser.getId());
                //Before or after syncing the user, you can customize here
                syncUser(externalUser);

                return true;
            } else {
               // if (log.isDebugEnabled()) {
                    if (userId != null) {
                        log.info("IDP {} returned null for simple creds of {}", idp.getName(), userId);
                    } else {
                        log.info("IDP {} returned null for {}", idp.getName(), credentials);
                    }
               // }

                if (sId != null) {
                    // invalidate the user if it exists as synced variant
                    log.debug("local user exists for '{}'. re-validating.", sId.getId());
                    validateUser(sId.getId());
                }
                return false;
            }
        } catch (ExternalIdentityException e) {

        	log.error("Error while authenticating with",
                     idp.getName(), e);
            return false;
        } catch (LoginException e) {

        	 log.debug("IDP {} throws login exception for '{}': {}",
                     e.getMessage());
            throw e;
        } catch (Exception e) {

        	log.debug("SyncHandler {} throws sync exception for '{}'", e);
            LoginException le = new LoginException("Error while syncing user.");
            le.initCause(e);
            throw le;
        }
    }

    @Override
    public boolean commit() throws LoginException {
    	log.info("######## Inside Custom External Login Module Commit Method #######");
        if (externalUser == null) {
            return false;
        }
        Set<? extends Principal> principals = getPrincipals(externalUser.getId());
        if (!principals.isEmpty()) {
            if (!subject.isReadOnly()) {
                subject.getPrincipals().addAll(principals);
                if (credentials != null) {
                    subject.getPublicCredentials().add(credentials);
                }
                setAuthInfo(createAuthInfo(externalUser.getId(), principals), subject);
            } else {
                log.debug("Could not add information to read only subject {}", subject);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean abort() throws LoginException {
        clearState();
        // do we need to remove the user again, in case we created it during login() ?
        return true;
    }

    /**
     * Initiates synchronization of the external user.
     * @param user the external user
     * @throws SyncException if an error occurs
     */
    private void syncUser(@Nonnull ExternalUser user) throws SyncException {
    	log.info("######## Inside Custom External Login Module SyncUser Method #######");
        Root root = getRoot();
        if (root == null) {
            throw new SyncException("Cannot synchronize user. root == null");
        }
        UserManager userManager = getUserManager();
        if (userManager == null) {
            throw new SyncException("Cannot synchronize user. userManager == null");
        }

        int numAttempt = 0;
        while (numAttempt++ < MAX_SYNC_ATTEMPTS) {
            SyncContext context = null;
            try {
                context = syncHandler.createContext(idp, userManager, new ValueFactoryImpl(root, NamePathMapper.DEFAULT));
                context.sync(user);
                root.commit();
                if (log.isDebugEnabled()) {
                    log.debug("syncUser({}) {}", user.getId());
                }
                return;
            } catch (CommitFailedException e) {
                root.refresh();
            } finally {
                if (context != null) {
                    context.close();
                }
            }
        }
        throw new SyncException("User synchronization failed during commit after " + MAX_SYNC_ATTEMPTS + " attempts");
    }

    /**
     * Initiates synchronization of a possible remove user
     * @param id the user id
     */
    private void validateUser(@Nonnull String id) throws SyncException {
    	log.info("######## Inside Custom External Login Module Validate User Method #######");
        SyncContext context = null;
        try {
            Root root = getRoot();
            if (root == null) {
                throw new SyncException("Cannot synchronize user. root == null");
            }
            UserManager userManager = getUserManager();
            if (userManager == null) {
                throw new SyncException("Cannot synchronize user. userManager == null");
            }
            context = syncHandler.createContext(idp, userManager, new ValueFactoryImpl(root, NamePathMapper.DEFAULT));
            context.sync(id);
            root.commit();
            if (log.isDebugEnabled()) {
                log.debug("validateUser({}) {}", id); 
            }
        } catch (CommitFailedException e) {
            throw new SyncException("User synchronization failed during commit.", e);
        } finally {
            if (context != null) {
                context.close();
            }
        }

    }

    @Nonnull
    private AuthInfo createAuthInfo(@Nonnull String userId, @Nonnull Set<? extends Principal> principals) {
        Credentials creds;
        log.info("######## Inside Custom External Login Module CreateAuthInfo Method #######");
        if (credentials instanceof ImpersonationCredentials) {
            creds = ((ImpersonationCredentials) credentials).getBaseCredentials();
        } else {
            creds = credentials;
        }
        Map<String, Object> attributes = new HashMap<String, Object>();
        Object shared = sharedState.get(SHARED_KEY_ATTRIBUTES);
        if (shared instanceof Map) {
            for (Object key : ((Map) shared).keySet()) {
                attributes.put(key.toString(), ((Map) shared).get(key));
            }
        } else if (creds instanceof SimpleCredentials) {
            SimpleCredentials sc = (SimpleCredentials) creds;
            for (String attrName : sc.getAttributeNames()) {
                attributes.put(attrName, sc.getAttribute(attrName));
            }
        }
        return new AuthInfoImpl(userId, attributes, principals);
    }

    //------------------------------------------------< AbstractLoginModule >---

    @Override
    protected void clearState() {
        super.clearState();
        externalUser = null;
        credentials = null;
    }

    /**
     * @return An immutable set containing only the {@link SimpleCredentials} class.
     */
    @Nonnull
    @Override
    protected Set<Class> getSupportedCredentials() {
        // TODO: maybe delegate getSupportedCredentials to IDP
        Class scClass = SimpleCredentials.class;
        return Collections.singleton(scClass);
    }
}
