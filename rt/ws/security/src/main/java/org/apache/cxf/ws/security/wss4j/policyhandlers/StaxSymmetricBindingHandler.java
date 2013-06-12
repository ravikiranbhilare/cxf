/**
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

package org.apache.cxf.ws.security.wss4j.policyhandlers;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.wss4j.policy.SPConstants.IncludeTokenType;
import org.apache.wss4j.policy.model.AbstractSymmetricAsymmetricBinding;
import org.apache.wss4j.policy.model.AbstractToken;
import org.apache.wss4j.policy.model.AbstractToken.DerivedKeys;
import org.apache.wss4j.policy.model.AbstractTokenWrapper;
import org.apache.wss4j.policy.model.AlgorithmSuite;
import org.apache.wss4j.policy.model.IssuedToken;
import org.apache.wss4j.policy.model.KerberosToken;
import org.apache.wss4j.policy.model.SecureConversationToken;
import org.apache.wss4j.policy.model.SecurityContextToken;
import org.apache.wss4j.policy.model.SpnegoContextToken;
import org.apache.wss4j.policy.model.SymmetricBinding;
import org.apache.wss4j.policy.model.UsernameToken;
import org.apache.wss4j.policy.model.X509Token;
import org.apache.wss4j.stax.ext.WSSConstants;
import org.apache.wss4j.stax.securityToken.WSSecurityTokenConstants;
import org.apache.xml.security.algorithms.JCEMapper;
import org.apache.xml.security.exceptions.XMLSecurityException;
import org.apache.xml.security.stax.ext.SecurePart;
import org.apache.xml.security.stax.ext.SecurePart.Modifier;
import org.apache.xml.security.stax.impl.securityToken.GenericOutboundSecurityToken;
import org.apache.xml.security.stax.impl.util.IDGenerator;
import org.apache.xml.security.stax.securityToken.OutboundSecurityToken;
import org.apache.xml.security.stax.securityToken.SecurityTokenProvider;
import org.apache.xml.security.utils.Base64;

/**
 * 
 */
public class StaxSymmetricBindingHandler extends AbstractStaxBindingHandler {
    
    private SymmetricBinding sbinding;
    private SoapMessage message;
    
    public StaxSymmetricBindingHandler(
        Map<String, Object> properties, 
        SoapMessage msg,
        Map<String, SecurityTokenProvider<OutboundSecurityToken>> outboundTokens
    ) {
        super(properties, msg, outboundTokens);
        this.message = msg;
    }
    
    private AbstractTokenWrapper getSignatureToken() {
        if (sbinding.getProtectionToken() != null) {
            return sbinding.getProtectionToken();
        }
        return sbinding.getSignatureToken();
    }
    
    private AbstractTokenWrapper getEncryptionToken() {
        if (sbinding.getProtectionToken() != null) {
            return sbinding.getProtectionToken();
        }
        return sbinding.getEncryptionToken();
    }
    
    public void handleBinding() {
        AssertionInfoMap aim = getMessage().get(AssertionInfoMap.class);
        configureTimestamp(aim);
        configureLayout(aim);
        sbinding = (SymmetricBinding)getBinding(aim);
        
        if (sbinding.getProtectionOrder() 
            == AbstractSymmetricAsymmetricBinding.ProtectionOrder.EncryptBeforeSigning) {
            doEncryptBeforeSign();
        } else {
            doSignBeforeEncrypt();
        }
    }
    
    private void doEncryptBeforeSign() {
        try {
            AbstractTokenWrapper encryptionWrapper = getEncryptionToken();
            AbstractToken encryptionToken = encryptionWrapper.getToken();

            //The encryption token can be an IssuedToken or a 
            //SecureConversationToken
            String tokenId = null;
            SecurityToken tok = null;
            if (encryptionToken instanceof IssuedToken 
                || encryptionToken instanceof KerberosToken
                || encryptionToken instanceof SecureConversationToken
                || encryptionToken instanceof SecurityContextToken
                || encryptionToken instanceof SpnegoContextToken) {
                tok = getSecurityToken();
            } else if (encryptionToken instanceof X509Token) {
                if (isRequestor()) {
                    tokenId = setupEncryptedKey(encryptionWrapper, encryptionToken);
                } else {
                    tokenId = getEncryptedKey();
                }
            } else if (encryptionToken instanceof UsernameToken) {
                policyNotAsserted(sbinding, "UsernameTokens not supported with Symmetric binding");
                return;
            }
            if (tok == null) {
                if (tokenId != null && tokenId.startsWith("#")) {
                    tokenId = tokenId.substring(1);
                }

                // Get hold of the token from the token storage
                tok = getTokenStore().getToken(tokenId);
            }
            
            // Store key
            storeSecurityToken(tok);
            
            List<SecurePart> encrParts = null;
            List<SecurePart> sigParts = null;
            try {
                encrParts = getEncryptedParts();
                //Signed parts are determined before encryption because encrypted signed headers
                //will not be included otherwise
                sigParts = getSignedParts();
            } catch (SOAPException ex) {
                throw new Fault(ex);
            }
            
            if (encryptionToken != null && encrParts.size() > 0) {
                if (isRequestor()) {
                    addSupportingTokens();
                    encrParts.addAll(encryptedTokensList);
                } else {
                    addSignatureConfirmation(sigParts);
                }
                
                //Check for signature protection
                if (sbinding.isEncryptSignature()) {
                    SecurePart part = 
                        new SecurePart(new QName(WSSConstants.NS_DSIG, "Signature"), Modifier.Element);
                    encrParts.add(part);
                }
                
                doEncryption(encryptionWrapper, encrParts, true);
                if (timestampAdded) {
                    SecurePart part = 
                        new SecurePart(new QName(WSSConstants.NS_WSU10, "Timestamp"), Modifier.Element);
                    sigParts.add(part);
                }
                
                AbstractTokenWrapper sigAbstractTokenWrapper = getSignatureToken();
                AbstractToken sigToken = sigAbstractTokenWrapper.getToken();
                if ((sigParts.size() > 0) && sigAbstractTokenWrapper != null && isRequestor()) {
                    doSignature(sigAbstractTokenWrapper, sigToken, tok, sigParts);
                } else if (!isRequestor()) {
                    addSignatureConfirmation(sigParts);
                    if (!sigParts.isEmpty()) {
                        doSignature(sigAbstractTokenWrapper, sigToken, tok, sigParts);
                    }
                }
    
                //if (isRequestor()) {
                //    doEndorse();
                //}
            }
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new Fault(ex);
        }
    }
    
    private void doSignBeforeEncrypt() {
        AbstractTokenWrapper sigAbstractTokenWrapper = getSignatureToken();
        AbstractToken sigToken = sigAbstractTokenWrapper.getToken();
        String sigTokId = null;
        
        try {
            SecurityToken sigTok = null;
            if (sigToken != null) {
                if (sigToken instanceof SecureConversationToken
                    || sigToken instanceof SecurityContextToken
                    || sigToken instanceof IssuedToken 
                    || sigToken instanceof KerberosToken
                    || sigToken instanceof SpnegoContextToken) {
                    sigTok = getSecurityToken();
                } else if (sigToken instanceof X509Token) {
                    if (isRequestor()) {
                        sigTokId = setupEncryptedKey(sigAbstractTokenWrapper, sigToken);
                    } else {
                        sigTokId = getEncryptedKey();
                    }
                } else if (sigToken instanceof UsernameToken) {
                    policyNotAsserted(sbinding, "UsernameTokens not supported with Symmetric binding");
                    return;
                }
            } else {
                policyNotAsserted(sbinding, "No signature token");
                return;
            }
            
            if (sigTok == null && StringUtils.isEmpty(sigTokId)) {
                policyNotAsserted(sigAbstractTokenWrapper, "No signature token id");
                return;
            }
            if (sigTok == null) {
                sigTok = getTokenStore().getToken(sigTokId);
            }
            
            // Store key
            storeSecurityToken(sigTok);

            // Add timestamp
            List<SecurePart> sigs = new ArrayList<SecurePart>();
            if (timestampAdded) {
                SecurePart part = 
                    new SecurePart(new QName(WSSConstants.NS_WSU10, "Timestamp"), Modifier.Element);
                sigs.add(part);
            }

            if (isRequestor()) {
                addSupportingTokens();
                if (!sigs.isEmpty()) {
                    doSignature(sigAbstractTokenWrapper, sigToken, sigTok, sigs);
                }
                // doEndorse();
            } else {
                addSignatureConfirmation(sigs);
                if (!sigs.isEmpty()) {
                    doSignature(sigAbstractTokenWrapper, sigToken, sigTok, sigs);
                }
            }

            //Encryption
            List<SecurePart> enc = getEncryptedParts();
            
            //Check for signature protection
            if (sbinding.isEncryptSignature()) {
                SecurePart part = 
                    new SecurePart(new QName(WSSConstants.NS_DSIG, "Signature"), Modifier.Element);
                enc.add(part);
            }
            
            //Do encryption
            if (isRequestor()) {
                enc.addAll(encryptedTokensList);
            }
            AbstractTokenWrapper encrAbstractTokenWrapper = getEncryptionToken();
            doEncryption(encrAbstractTokenWrapper, enc, false);
        } catch (Exception e) {
            throw new Fault(e);
        }
    }
    
    private void doEncryption(AbstractTokenWrapper recToken,
                              List<SecurePart> encrParts,
                              boolean externalRef) throws SOAPException {
        //Do encryption
        if (recToken != null && recToken.getToken() != null && encrParts.size() > 0) {
            AbstractToken encrToken = recToken.getToken();
            AlgorithmSuite algorithmSuite = sbinding.getAlgorithmSuite();

            // Action
            Map<String, Object> config = getProperties();
            String actionToPerform = ConfigurationConstants.ENCRYPT;
            if (recToken.getToken().getDerivedKeys() == DerivedKeys.RequireDerivedKeys) {
                actionToPerform = ConfigurationConstants.ENCRYPT_DERIVED;
            }

            if (config.containsKey(ConfigurationConstants.ACTION)) {
                String action = (String)config.get(ConfigurationConstants.ACTION);
                config.put(ConfigurationConstants.ACTION, action + " " + actionToPerform);
            } else {
                config.put(ConfigurationConstants.ACTION, actionToPerform);
            }

            String parts = "";
            if (config.containsKey(ConfigurationConstants.ENCRYPTION_PARTS)) {
                parts = (String)config.get(ConfigurationConstants.ENCRYPTION_PARTS);
                if (!parts.endsWith(";")) {
                    parts += ";";
                }
            }

            for (SecurePart part : encrParts) {
                QName name = part.getName();
                parts += "{" + part.getModifier() + "}{"
                    +  name.getNamespaceURI() + "}" + name.getLocalPart() + ";";
            }

            config.put(ConfigurationConstants.ENCRYPTION_PARTS, parts);

            config.put(ConfigurationConstants.ENC_KEY_ID, 
                       getKeyIdentifierType(recToken, encrToken));

            config.put(ConfigurationConstants.ENC_KEY_TRANSPORT, 
                       algorithmSuite.getAlgorithmSuiteType().getAsymmetricKeyWrap());
            config.put(ConfigurationConstants.ENC_SYM_ALGO, 
                       algorithmSuite.getAlgorithmSuiteType().getEncryption());

            String encUser = (String)message.getContextualProperty(SecurityConstants.ENCRYPT_USERNAME);
            if (encUser != null) {
                config.put(ConfigurationConstants.ENCRYPTION_USER, encUser);
            }
        }
    }
    
    private void doSignature(AbstractTokenWrapper wrapper, AbstractToken policyToken, 
                             SecurityToken tok, List<SecurePart> sigParts) 
        throws WSSecurityException, SOAPException {
        
        // Action
        Map<String, Object> config = getProperties();
        String actionToPerform = ConfigurationConstants.SIGNATURE;
        if (wrapper.getToken().getDerivedKeys() == DerivedKeys.RequireDerivedKeys) {
            actionToPerform = ConfigurationConstants.SIGNATURE_DERIVED;
        }
        
        if (config.containsKey(ConfigurationConstants.ACTION)) {
            String action = (String)config.get(ConfigurationConstants.ACTION);
            if (!action.contains(ConfigurationConstants.SAML_TOKEN_SIGNED)) {
                config.put(ConfigurationConstants.ACTION, action + " " + actionToPerform);
            }
        } else {
            config.put(ConfigurationConstants.ACTION, actionToPerform);
        }
        
        String parts = "";
        if (config.containsKey(ConfigurationConstants.SIGNATURE_PARTS)) {
            parts = (String)config.get(ConfigurationConstants.SIGNATURE_PARTS);
            if (!parts.endsWith(";")) {
                parts += ";";
            }
        }
        
        sigParts.addAll(this.getSignedParts());
        
        for (SecurePart part : sigParts) {
            QName name = part.getName();
            parts += "{Element}{" +  name.getNamespaceURI() + "}" + name.getLocalPart() + ";";
        }
        
        AbstractToken sigToken = wrapper.getToken();
        if (sbinding.isProtectTokens() && (sigToken instanceof X509Token)
            && sigToken.getIncludeTokenType() != IncludeTokenType.INCLUDE_TOKEN_NEVER) {
            parts += "{Element}{" + WSSConstants.NS_WSSE10 + "}BinarySecurityToken;";
        }
        
        config.put(ConfigurationConstants.SIGNATURE_PARTS, parts);
        
        configureSignature(wrapper, sigToken, false);
        
        if (policyToken instanceof X509Token) {
            config.put(ConfigurationConstants.INCLUDE_SIGNATURE_TOKEN, "false");
            if (isRequestor()) {
                config.put(ConfigurationConstants.SIG_KEY_ID, "EncryptedKey");
            } else {
                config.put(ConfigurationConstants.SIG_KEY_ID, "EncryptedKeySHA1");
                // TODO sig.setEncrKeySha1value(tok.getSHA1());
            }
        }
        
        if (sigToken.getDerivedKeys() == DerivedKeys.RequireDerivedKeys) {
            config.put(ConfigurationConstants.SIG_ALGO, 
                   sbinding.getAlgorithmSuite().getSymmetricSignature());
        }
    }

    private String setupEncryptedKey(AbstractTokenWrapper wrapper, AbstractToken sigToken) throws WSSecurityException {
        
        Date created = new Date();
        Date expires = new Date();
        expires.setTime(created.getTime() + 300000);
        SecurityToken tempTok = 
            new SecurityToken(IDGenerator.generateID(null), created, expires);
        
        KeyGenerator keyGenerator = 
            getKeyGenerator(sbinding.getAlgorithmSuite().getAlgorithmSuiteType().getEncryption());
        SecretKey symmetricKey = keyGenerator.generateKey();
        tempTok.setKey(symmetricKey);
        tempTok.setSecret(symmetricKey.getEncoded());
        
        // Set the SHA1 value of the encrypted key, this is used when the encrypted
        // key is referenced via a key identifier of type EncryptedKeySHA1
        // tempTok.setSHA1(getSHA1(encrKey.getEncryptedEphemeralKey()));
        
        getTokenStore().add(tempTok);
        
        return tempTok.getId();
    }
    
    private String getEncryptedKey() {
        
        List<WSHandlerResult> results = CastUtils.cast((List<?>)message.getExchange().getInMessage()
            .get(WSHandlerConstants.RECV_RESULTS));
        
        for (WSHandlerResult rResult : results) {
            List<WSSecurityEngineResult> wsSecEngineResults = rResult.getResults();
            
            for (WSSecurityEngineResult wser : wsSecEngineResults) {
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                String encryptedKeyID = (String)wser.get(WSSecurityEngineResult.TAG_ID);
                if (actInt.intValue() == WSConstants.ENCR
                    && encryptedKeyID != null
                    && encryptedKeyID.length() != 0) {
                    Date created = new Date();
                    Date expires = new Date();
                    expires.setTime(created.getTime() + 300000);
                    SecurityToken tempTok = new SecurityToken(encryptedKeyID, created, expires);
                    tempTok.setSecret((byte[])wser.get(WSSecurityEngineResult.TAG_SECRET));
                    tempTok.setSHA1(getSHA1((byte[])wser
                                            .get(WSSecurityEngineResult.TAG_ENCRYPTED_EPHEMERAL_KEY)));
                    getTokenStore().add(tempTok);
                    
                    return encryptedKeyID;
                }
            }
        }
        return null;
    }
    
    private String getSHA1(byte[] input) {
        try {
            byte[] digestBytes = WSSecurityUtil.generateDigest(input);
            return Base64.encode(digestBytes);
        } catch (WSSecurityException e) {
            //REVISIT
        }
        return null;
    }
    
    private KeyGenerator getKeyGenerator(String symEncAlgo) throws WSSecurityException {
        try {
            //
            // Assume AES as default, so initialize it
            //
            WSSConfig.init();
            String keyAlgorithm = JCEMapper.getJCEKeyAlgorithmFromURI(symEncAlgo);
            if (keyAlgorithm == null || "".equals(keyAlgorithm)) {
                keyAlgorithm = JCEMapper.translateURItoJCEID(symEncAlgo);
            }
            KeyGenerator keyGen = KeyGenerator.getInstance(keyAlgorithm);
            if (symEncAlgo.equalsIgnoreCase(WSConstants.AES_128)
                || symEncAlgo.equalsIgnoreCase(WSConstants.AES_128_GCM)) {
                keyGen.init(128);
            } else if (symEncAlgo.equalsIgnoreCase(WSConstants.AES_192)
                || symEncAlgo.equalsIgnoreCase(WSConstants.AES_192_GCM)) {
                keyGen.init(192);
            } else if (symEncAlgo.equalsIgnoreCase(WSConstants.AES_256)
                || symEncAlgo.equalsIgnoreCase(WSConstants.AES_256_GCM)) {
                keyGen.init(256);
            }
            return keyGen;
        } catch (NoSuchAlgorithmException e) {
            throw new WSSecurityException(
                WSSecurityException.ErrorCode.UNSUPPORTED_ALGORITHM, e
            );
        }
    }
    
    private void storeSecurityToken(SecurityToken tok) {
        final GenericOutboundSecurityToken encryptedKeySecurityToken = 
            new GenericOutboundSecurityToken(tok.getId(), WSSecurityTokenConstants.EncryptedKeyToken, tok.getKey());
        
        final SecurityTokenProvider<OutboundSecurityToken> encryptedKeySecurityTokenProvider =
            new SecurityTokenProvider<OutboundSecurityToken>() {

                @Override
                public OutboundSecurityToken getSecurityToken() throws XMLSecurityException {
                    return encryptedKeySecurityToken;
                }

                @Override
                public String getId() {
                    return encryptedKeySecurityToken.getId();
                }
            };
        outboundTokens.put(WSSConstants.PROP_USE_THIS_TOKEN_ID_FOR_ENCRYPTION, 
                           encryptedKeySecurityTokenProvider);
        outboundTokens.put(WSSConstants.PROP_USE_THIS_TOKEN_ID_FOR_SIGNATURE, 
                           encryptedKeySecurityTokenProvider);
    }
}
