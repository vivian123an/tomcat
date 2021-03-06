/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.X509KeyManager;

import org.apache.tomcat.util.net.jsse.NioX509KeyManager;

public abstract class AbstractJsseEndpoint<S> extends AbstractEndpoint<S> {

    private SSLImplementation sslImplementation = null;
    private Map<String,SSLContextWrapper> sslContexts = new HashMap<>();

    public SSLImplementation getSslImplementation() {
        return sslImplementation;
    }


    protected void initialiseSsl() throws Exception {
        if (isSSLEnabled()) {
            sslImplementation = SSLImplementation.getInstance(getSslImplementationName());

            for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
                SSLUtil sslUtil = sslImplementation.getSSLUtil(this, sslHostConfig);
                SSLContext sslContext = sslUtil.createSSLContext();
                sslContext.init(wrap(sslUtil.getKeyManagers()),
                        sslUtil.getTrustManagers(), null);

                SSLSessionContext sessionContext =
                    sslContext.getServerSessionContext();
                if (sessionContext != null) {
                    sslUtil.configureSessionContext(sessionContext);
                }
                SSLContextWrapper sslContextWrapper = new SSLContextWrapper(sslContext, sslUtil);
                sslContexts.put(sslHostConfig.getHostName(), sslContextWrapper);
            }
        }
    }


    protected SSLEngine createSSLEngine(String sniHostName) {
        SSLContextWrapper sslContextWrapper = getSSLContextWrapper(sniHostName);

        SSLEngine engine = sslContextWrapper.getSSLContext().createSSLEngine();
        if ("false".equals(getClientAuth())) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth())){
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth())) {
            engine.setWantClientAuth(true);
        }
        engine.setUseClientMode(false);
        engine.setEnabledCipherSuites(sslContextWrapper.getEnabledCiphers());
        engine.setEnabledProtocols(sslContextWrapper.getEnabledProtocols());

        configureUseServerCipherSuitesOrder(engine);

        return engine;
    }



    @Override
    public void unbind() throws Exception {
        sslContexts.clear();
    }


    /**
     * Configures SSLEngine to honor cipher suites ordering based upon
     * endpoint configuration.
     */
    private void configureUseServerCipherSuitesOrder(SSLEngine engine) {
        String useServerCipherSuitesOrderStr = this
                .getUseServerCipherSuitesOrder().trim();

        SSLParameters sslParameters = engine.getSSLParameters();
        boolean useServerCipherSuitesOrder =
            ("true".equalsIgnoreCase(useServerCipherSuitesOrderStr)
                || "yes".equalsIgnoreCase(useServerCipherSuitesOrderStr));

        sslParameters.setUseCipherSuitesOrder(useServerCipherSuitesOrder);
        engine.setSSLParameters(sslParameters);
    }


    private KeyManager[] wrap(KeyManager[] managers) {
        if (managers==null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i=0; i<result.length; i++) {
            if (managers[i] instanceof X509KeyManager && getKeyAlias()!=null) {
                result[i] = new NioX509KeyManager((X509KeyManager)managers[i],getKeyAlias());
            } else {
                result[i] = managers[i];
            }
        }
        return result;
    }


    private SSLContextWrapper getSSLContextWrapper(String sniHostName) {
        // First choice - direct match
        SSLContextWrapper result = sslContexts.get(sniHostName);
        if (result != null) {
            return result;
        }
        // Second choice, wildcard match
        int indexOfDot = sniHostName.indexOf('.');
        if (indexOfDot > -1) {
            result = sslContexts.get("*" + sniHostName.substring(indexOfDot));
        }
        // Fall-back. Use the default
        if (result == null) {
            result = sslContexts.get(SSLHostConfig.DEFAULT_SSL_HOST_NAME);
        }
        if (result == null) {
            // Should never happen.
            throw new IllegalStateException();
        }
        return result;
    }


    private static class SSLContextWrapper {

        private final SSLContext sslContext;
        private final String[] enabledCiphers;
        private final String[] enabledProtocols;

        public SSLContextWrapper(SSLContext sslContext, SSLUtil sslUtil) {
            this.sslContext = sslContext;
            // Determine which cipher suites and protocols to enable
            enabledCiphers = sslUtil.getEnableableCiphers(sslContext);
            enabledProtocols = sslUtil.getEnableableProtocols(sslContext);
        }

        public SSLContext getSSLContext() { return sslContext;}
        public String[] getEnabledCiphers() { return enabledCiphers; }
        public String[] getEnabledProtocols() { return enabledProtocols; }
    }
}
