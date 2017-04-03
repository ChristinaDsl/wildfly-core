/*
 * JBoss, Home of Professional Open Source
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.controller.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.StringTokenizer;

import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.AttributeMarshaller;
import org.jboss.as.controller.AttributeParser;
import org.jboss.as.controller.CapabilityReferenceRecorder;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.security.auth.SupportLevel;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.credential.source.CommandCredentialSource;
import org.wildfly.security.credential.source.CredentialSource;
import org.wildfly.security.credential.source.CredentialStoreCredentialSource;
import org.wildfly.security.credential.store.CredentialStore;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.util.Alphabet;
import org.wildfly.security.util.PasswordBasedEncryptionUtil;

/**
 * Utility class holding attribute definitions for credential-reference attribute in the model.
 * The class is unifying access to credentials defined through {@link org.wildfly.security.credential.store.CredentialStore}.
 *
 * It defines credential-reference attribute that other subsystems can use to reference external credentials of various
 * types.
 *
 * @author <a href="mailto:pskopek@redhat.com">Peter Skopek</a>
 */
public final class CredentialReference {

    /**
     * Capability required by a credential-reference attribute if its {@code store} field is configured.
     */
    public static final String CREDENTIAL_STORE_CAPABILITY = "org.wildfly.security.credential-store";
    /**
     * Standard name of a credential reference attribute.
     */
    public static final String CREDENTIAL_REFERENCE = "credential-reference";
    /**
     * Name of a field in the complex credential reference attribute.
     */
    public static final String STORE = "store";
    /**
     * Name of a field in the complex credential reference attribute.
     */
    public static final String ALIAS = "alias";
    /**
     * Name of a field in the complex credential reference attribute.
     */
    public static final String TYPE = "type";
    /**
     * Name of a field in the complex credential reference attribute.
     */
    public static final String CLEAR_TEXT = "clear-text";

    private static final SimpleAttributeDefinition credentialStoreAttribute;
    private static final SimpleAttributeDefinition credentialAliasAttribute;
    private static final SimpleAttributeDefinition credentialTypeAttribute;
    private static final SimpleAttributeDefinition clearTextAttribute;

    /** A variant that has a default capability reference configured for the attribute */
    private static final SimpleAttributeDefinition credentialStoreAttributeWithCapabilityReference;

    private static final ObjectTypeAttributeDefinition credentialReferenceAD;

    /** Uses credentialStoreAttributeWithCapabilityReference */
    private static final ObjectTypeAttributeDefinition credentialReferenceADWithCapabilityReference;

    static {
        credentialStoreAttribute = new SimpleAttributeDefinitionBuilder(STORE, ModelType.STRING, true)
                .setXmlName(STORE)
                .build();
        credentialAliasAttribute = new SimpleAttributeDefinitionBuilder(ALIAS, ModelType.STRING, true)
                .setXmlName(ALIAS)
                .setAllowExpression(true)
                .build();
        credentialTypeAttribute = new SimpleAttributeDefinitionBuilder(TYPE, ModelType.STRING, true)
                .setXmlName(TYPE)
                .setAllowExpression(true)
                .build();
        clearTextAttribute = new SimpleAttributeDefinitionBuilder(CLEAR_TEXT, ModelType.STRING, true)
                .setXmlName(CLEAR_TEXT)
                .setAllowExpression(true)
                .build();
        credentialReferenceAD = getAttributeBuilder(CREDENTIAL_REFERENCE, CREDENTIAL_REFERENCE, false, false).build();

        credentialStoreAttributeWithCapabilityReference = new SimpleAttributeDefinitionBuilder(credentialStoreAttribute)
                .setCapabilityReference(CREDENTIAL_STORE_CAPABILITY)
                .build();

        credentialReferenceADWithCapabilityReference = getAttributeBuilder(CREDENTIAL_REFERENCE, CREDENTIAL_REFERENCE, false, true).build();
    }

    private CredentialReference() {
    }

    // utility static methods

    /**
     * Returns a definition for a credential reference attribute. The {@code store} field in the
     * attribute does not register any requirement for a credential store capability.
     *
     * @return credential reference attribute definition
     *
     */
    public static ObjectTypeAttributeDefinition getAttributeDefinition() {
        return credentialReferenceAD;
    }

    /**
     * Returns a definition for a credential reference attribute, one that optionally
     * {@link org.jboss.as.controller.AbstractAttributeDefinitionBuilder#setCapabilityReference(String) registers a requirement}
     * for a {@link #CREDENTIAL_STORE_CAPABILITY credential store capability}.
     * If a requirement is registered, the dependent capability will be the single capability registered by the
     * resource that uses this attribute definition. The resource must expose one and only one capability in order
     * to use this facility.
     *
     * @param referenceCredentialStore {@code true} if the {@code store} field in the
     *                                 attribute should register a requirement for a credential store capability.
     *
     * @return credential reference attribute definition
     */
    public static ObjectTypeAttributeDefinition getAttributeDefinition(boolean referenceCredentialStore) {
        return referenceCredentialStore
                ? credentialReferenceADWithCapabilityReference
                : credentialReferenceAD;
    }

    /**
     * Gets an attribute builder for a credential-reference attribute with the standard {@code credential-reference}
     * attribute name, a configurable setting as to whether the attribute is required, and optionally configured to
     * {@link org.jboss.as.controller.AbstractAttributeDefinitionBuilder#setCapabilityReference(String) register a requirement}
     * for a {@link #CREDENTIAL_STORE_CAPABILITY credential store capability}.
     * If a requirement is registered, the dependent capability will be the single capability registered by the
     * resource that uses this attribute definition. The resource must expose one and only one capability in order
     * to use this facility.
     *
     * @param allowNull whether the attribute is required
     * @param referenceCredentialStore {@code true} if the {@code store} field in the
     *                                 attribute should register a requirement for a credential store capability.
     * @return an {@link ObjectTypeAttributeDefinition.Builder} which can be used to build an attribute definition
     */
    public static ObjectTypeAttributeDefinition.Builder getAttributeBuilder(boolean allowNull, boolean referenceCredentialStore) {
        AttributeDefinition csAttr = referenceCredentialStore ? credentialStoreAttributeWithCapabilityReference : credentialStoreAttribute;
        return getAttributeBuilder(CREDENTIAL_REFERENCE, CREDENTIAL_REFERENCE, allowNull, csAttr);
    }

    /**
     * Get an attribute builder for a credential-reference attribute with the specified characteristics. The
     * {@code store} field in the attribute does not register any requirement for a credential store capability.
     *
     * @param name name of attribute
     * @param xmlName name of xml element
     * @param allowNull {@code false} if the attribute is required
     * @return an {@link ObjectTypeAttributeDefinition.Builder} which can be used to build an attribute definition
     */
    public static ObjectTypeAttributeDefinition.Builder getAttributeBuilder(String name, String xmlName, boolean allowNull) {
        return getAttributeBuilder(name, xmlName, allowNull, false);
    }

    /**
     * Get an attribute builder for a credential-reference attribute with the specified characteristics, optionally configured to
     * {@link org.jboss.as.controller.AbstractAttributeDefinitionBuilder#setCapabilityReference(String) register a requirement}
     * for a {@link #CREDENTIAL_STORE_CAPABILITY credential store capability}.
     * If a requirement is registered, the dependent capability will be the single capability registered by the
     * resource that uses this attribute definition. The resource must expose one and only one capability in order
     * to use this facility.
     *
     * @param name name of attribute
     * @param xmlName name of xml element
     * @param allowNull {@code false} if the attribute is required
     * @param referenceCredentialStore {@code true} if the {@code store} field in the
     *                                 attribute should register a requirement for a credential store capability.
     * @return an {@link ObjectTypeAttributeDefinition.Builder} which can be used to build an attribute definition
     */
    public static ObjectTypeAttributeDefinition.Builder getAttributeBuilder(String name, String xmlName,
                                                                            boolean allowNull, boolean referenceCredentialStore) {
        AttributeDefinition csAttr = referenceCredentialStore ? credentialStoreAttributeWithCapabilityReference : credentialStoreAttribute;
        return getAttributeBuilder(name, xmlName, allowNull, csAttr);
    }

    /**
     * Get an attribute builder for a credential-reference attribute with the specified characteristics, optionally configured to
     * {@link org.jboss.as.controller.AbstractAttributeDefinitionBuilder#setCapabilityReference(CapabilityReferenceRecorder)} register a requirement}
     * for a {@link #CREDENTIAL_STORE_CAPABILITY credential store capability}.
     *
     * @param name name of attribute
     * @param xmlName name of xml element
     * @param allowNull {@code false} if the attribute is required
     * @param capabilityStoreReferenceRecorder a capability reference recorder that can record a requirement
     *                                         for the credential store referenced by the {@code store}
     *                                         field of the returned attribute definition. Can be {@code null},
     *                                         in which case no requirement would be recorded. If not {@code null}
     *                                         the recorder's
     *                                         {@link CapabilityReferenceRecorder#getBaseRequirementName() base requirement name}
     *                                         must equal {@link #CREDENTIAL_STORE_CAPABILITY}
     *
     * @return an {@link ObjectTypeAttributeDefinition.Builder} which can be used to build attribute definition
     */
    public static ObjectTypeAttributeDefinition.Builder getAttributeBuilder(String name, String xmlName, boolean allowNull,
                                                                            CapabilityReferenceRecorder capabilityStoreReferenceRecorder) {
        if (capabilityStoreReferenceRecorder == null) {
            return getAttributeBuilder(name, xmlName, allowNull, false);
        }

        assert CREDENTIAL_STORE_CAPABILITY.equals(capabilityStoreReferenceRecorder.getBaseRequirementName());
        AttributeDefinition csAttr = new SimpleAttributeDefinitionBuilder(credentialStoreAttribute)
                .setCapabilityReference(capabilityStoreReferenceRecorder)
                .build();
        return getAttributeBuilder(name, xmlName, allowNull, csAttr);
    }

    private static ObjectTypeAttributeDefinition.Builder getAttributeBuilder(String name, String xmlName, boolean allowNull, AttributeDefinition credentialStoreDefinition) {
        return new ObjectTypeAttributeDefinition.Builder(name, credentialStoreDefinition, credentialAliasAttribute, credentialTypeAttribute, clearTextAttribute)
                .setXmlName(xmlName)
                .setAttributeMarshaller(AttributeMarshaller.ATTRIBUTE_OBJECT)
                .setAttributeParser(AttributeParser.OBJECT_PARSER)
                .setAllowNull(allowNull)
                .setAccessConstraints(SensitiveTargetAccessConstraintDefinition.CREDENTIAL);
    }

    /**
     * Utility method to return part of {@link ObjectTypeAttributeDefinition} for credential reference attribute.
     *
     * {@see CredentialReference#getAttributeDefinition}
     * @param credentialReferenceValue value of credential reference attribute
     * @param name name of part to return (supported names: {@link #STORE} {@link #ALIAS} {@link #TYPE}
     *    {@link #CLEAR_TEXT}
     * @return value of part as {@link String}
     * @throws OperationFailedException when something goes wrong
     */
    public static String credentialReferencePartAsStringIfDefined(ModelNode credentialReferenceValue, String name) throws OperationFailedException {
        assert credentialReferenceValue.isDefined() : credentialReferenceValue;
        ModelNode result = credentialReferenceValue.get(name);
        if (result.isDefined()) {
            return result.asString();
        }
        return null;
    }

    /**
     * Get the ExceptionSupplier of {@link CredentialSource} which might throw an Exception while getting it.
     * {@link CredentialSource} is used later to retrieve the credential requested by configuration.
     *
     * @param context operation context
     * @param credentialReferenceAttributeDefinition credential-reference attribute definition
     * @param model containing the actual values
     * @param serviceBuilder of service which needs the credential
     * @return ExceptionSupplier of CredentialSource
     * @throws OperationFailedException wrapping exception when something goes wrong
     */
    public static ExceptionSupplier<CredentialSource, Exception> getCredentialSourceSupplier(OperationContext context, ObjectTypeAttributeDefinition credentialReferenceAttributeDefinition, ModelNode model, ServiceBuilder<?> serviceBuilder) throws OperationFailedException {

        ModelNode value = credentialReferenceAttributeDefinition.resolveModelAttribute(context, model);

        final String credentialStoreName;
        final String credentialAlias;
        final String credentialType;
        final String secret;

        if (value.isDefined()) {
            credentialStoreName = credentialReferencePartAsStringIfDefined(value, CredentialReference.STORE);
            credentialAlias = credentialReferencePartAsStringIfDefined(value, CredentialReference.ALIAS);
            credentialType = credentialReferencePartAsStringIfDefined(value, CredentialReference.TYPE);
            secret = credentialReferencePartAsStringIfDefined(value, CredentialReference.CLEAR_TEXT);
        } else {
            credentialStoreName = null;
            credentialAlias = null;
            credentialType = null;
            secret = null;
        }

        final InjectedValue<CredentialStore> credentialStoreInjectedValue = new InjectedValue<>();
        if (credentialAlias != null) {
            // use credential store service
            String credentialStoreCapabilityName = RuntimeCapability.buildDynamicCapabilityName(CREDENTIAL_STORE_CAPABILITY, credentialStoreName);
            ServiceName credentialStoreServiceName = context.getCapabilityServiceName(credentialStoreCapabilityName, CredentialStore.class);
            serviceBuilder.addDependency(ServiceBuilder.DependencyType.REQUIRED, credentialStoreServiceName, CredentialStore.class, credentialStoreInjectedValue);
        }

        return new ExceptionSupplier<CredentialSource, Exception>() {

            private String[] parseCommand(String command, String delimiter) {
                // comma can be back slashed
                final String[] parsedCommand = command.split("(?<!\\\\)" + delimiter);
                for (int k = 0; k < parsedCommand.length; k++) {
                    if (parsedCommand[k].indexOf('\\') != -1)
                        parsedCommand[k] = parsedCommand[k].replaceAll("\\\\" + delimiter, delimiter);
                }
                return parsedCommand;
            }

            private String stripType(String commandSpec) {
                StringTokenizer tokenizer = new StringTokenizer(commandSpec, "{}");
                tokenizer.nextToken();
                return tokenizer.nextToken();
            }

            /**
             * Gets a Credential Store Supplier.
             *
             * @return a supplier
             */
            @Override
            public CredentialSource get() throws Exception {
                if (credentialAlias != null) {
                    return new CredentialStoreCredentialSource(credentialStoreInjectedValue.getValue(), credentialAlias);
                } else if (credentialType != null && credentialType.equalsIgnoreCase("COMMAND")) {
                    CommandCredentialSource.Builder command = CommandCredentialSource.builder();
                    String commandSpec = secret.trim();
                    String[] parts;
                    if (commandSpec.startsWith("{EXT")) {
                        parts = parseCommand(stripType(commandSpec), " ");  // space delimited
                    } else if (commandSpec.startsWith("{CMD")) {
                        parts = parseCommand(stripType(commandSpec), ",");  // comma delimited
                    } else {
                        parts = parseCommand(commandSpec, " ");
                    }
                    for(String part: parts) {
                        command.addCommand(part);
                    }
                    return command.build();
                } else if (secret != null && secret.startsWith("MASK-")) {
                    // simple MASK- string with PicketBox compatibility and fixed algorithm and initial key material
                    return new CredentialSource() {
                        @Override
                        public SupportLevel getCredentialAcquireSupport(Class<? extends Credential> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                            return credentialType == PasswordCredential.class ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED;
                        }

                        @Override
                        public <C extends Credential> C getCredential(Class<C> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                            final String DEFAULT_ALGORITHM = "PBEWithMD5AndDES";
                            final String DEFAULT_PICKETBOX_INITIAL_KEY_MATERIAL = "somearbitrarycrazystringthatdoesnotmatter";
                            String[] part = secret.substring(5).split(";");  // strip "MASK-" and split by ';'
                            if (part.length != 3) {
                                throw ControllerLogger.ROOT_LOGGER.wrongMaskedPasswordFormat();
                            }
                            String salt = part[1];
                            final int iterationCount;
                            try {
                                iterationCount = Integer.parseInt(part[2]);
                            } catch (NumberFormatException e) {
                                throw ControllerLogger.ROOT_LOGGER.wrongMaskedPasswordFormat();
                            }
                            try {
                                PasswordBasedEncryptionUtil decryptUtil = new PasswordBasedEncryptionUtil.Builder()
                                        .alphabet(Alphabet.Base64Alphabet.PICKETBOX_COMPATIBILITY)
                                        .keyAlgorithm(DEFAULT_ALGORITHM)
                                        .password(DEFAULT_PICKETBOX_INITIAL_KEY_MATERIAL)
                                        .salt(salt)
                                        .iteration(iterationCount)
                                        .decryptMode()
                                        .build();
                                return credentialType.cast(new PasswordCredential(ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR,
                                        decryptUtil.decodeAndDecrypt(part[0]))));
                            } catch (GeneralSecurityException e) {
                                throw new IOException(e);
                            }
                        }
                    };
                } else {
                    if (secret != null) {
                        // clear text password
                        return new CredentialSource() {
                            @Override
                            public SupportLevel getCredentialAcquireSupport(Class<? extends Credential> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                                return credentialType == PasswordCredential.class ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED;
                            }

                            @Override
                            public <C extends Credential> C getCredential(Class<C> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                                return credentialType.cast(new PasswordCredential(ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, secret.toCharArray())));
                            }
                        };
                    } else {
                        return null;  // this indicates use of original method to get password from configuration
                    }
                }
            }
        };
    }

}
