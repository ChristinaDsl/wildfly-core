/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller;

import java.util.function.Supplier;

import org.jboss.as.controller.persistence.SubsystemMarshallingContext;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.staxmapper.XMLElementWriter;

/**
 * A subsystem registration.
 * <p>
 * If no XML mappings are defined, then a simple empty XML mapping is used.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public interface SubsystemRegistration {

    /**
     * Mark a subsystem as host capable. This will only take effect if running on a HC, and be ignored on a standalone or managed server.
     * The standard behaviour on the HC is to register the subsystem resource definitions in the domain model under the {@code /profile=*} address.
     * If this method is called, the standard behaviour happens, but in addition the resource definitions are registered in the local host model
     * so that the subsystem can be added there as well.
     */
    void setHostCapable();

    /**
     * Get the model node registration for this subsystem.
     *
     * @param resourceDefinition  factory for the provider of the description of the subsystem's root management resource
     * @return the subsystem-level model node registration
     */
    ManagementResourceRegistration registerSubsystemModel(ResourceDefinition resourceDefinition);

    /**
     * Get the deployment model node registration for this subsystem.
     *
     * @param resourceDefinition factory for the provider of the description of the subsystem's root deployment-level management resource
     * @return the deployment-level model node registration
     */
    ManagementResourceRegistration registerDeploymentModel(ResourceDefinition resourceDefinition);


    /**
     * Registers the {@link XMLElementWriter} that can handle marshalling
     * the subsystem's configuration to XML.
     *
     * @param writer the writer
     */
    void registerXMLElementWriter(XMLElementWriter<SubsystemMarshallingContext> writer);

    /**
     * Registers the {@link XMLElementWriter} that can handle marshalling
     * the subsystem's configuration to XML.
     *
     * @param writer the writer
     */
    default void registerXMLElementWriter(Supplier<XMLElementWriter<SubsystemMarshallingContext>> writer) {
        registerXMLElementWriter(writer.get());
    }

    /**
     * Get the version of the subsystem
     *
     * @return the version
     */
    ModelVersion getSubsystemVersion();

}
