/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.dcp.api.rest;

import org.jboss.dcp.api.annotations.header.CORSSupport;
import org.jboss.dcp.api.annotations.security.ProviderAllowed;
import org.jboss.dcp.api.service.ProviderService;
import org.jboss.dcp.api.service.SecurityService;

import javax.annotation.PostConstruct;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;
import java.util.Map;

/**
 * Provider REST API
 * 
 * @author Libor Krzyzanek
 * @author Vlastimil Elias (velias at redhat dot com)
 * 
 */
@RequestScoped
@Path("/provider")
@ProviderAllowed(superProviderOnly = true)
public class ProviderRestService extends RestEntityServiceBase {

	@Inject
	protected ProviderService providerService;

	@Inject
	protected SecurityService securityService;

	@Context
	protected SecurityContext securityContext;

	@PostConstruct
	public void init() {
		setEntityService(providerService);
	}

	protected static final String[] FIELDS_TO_REMOVE = new String[] { ProviderService.PASSWORD_HASH };

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
    @CORSSupport
	public Object getAll(@QueryParam("from") Integer from, @QueryParam("size") Integer size) {
		try {
			return entityService.getAll(from, size, FIELDS_TO_REMOVE);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
	}

	@GET
	@Path("/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@ProviderAllowed
	@Override
    @CORSSupport
	public Object get(@PathParam("id") String id) {

		if (id == null || id.isEmpty()) {
			return createRequiredFieldResponse("id");
		}

		// check Provider name. Only provider with same name or superprovider has access.
		String provider = securityContext.getUserPrincipal().getName();
		try {
			Map<String, Object> entity = entityService.get(id);

			if (entity == null)
				return Response.status(Status.NOT_FOUND).build();

			if (!provider.equals(entity.get(ProviderService.NAME))) {
				if (!providerService.isSuperProvider(provider)) {
					return Response.status(Status.FORBIDDEN).build();
				}
			}
			return ESDataOnlyResponse.removeFields(entity, FIELDS_TO_REMOVE);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
	}

	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Object create(Map<String, Object> data) {
		String nameFromData = (String) data.get(ProviderService.NAME);
		if (nameFromData == null || nameFromData.isEmpty())
			return Response.status(Status.BAD_REQUEST).entity("Required data field '" + ProviderService.NAME + "' not set")
					.build();
		return this.create(nameFromData, data);
	}

	@POST
	@Path("/{id}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Object create(@PathParam("id") String id, Map<String, Object> data) {

		if (id == null || id.isEmpty()) {
			return createRequiredFieldResponse("id");
		}

		String nameFromData = (String) data.get(ProviderService.NAME);
		if (nameFromData == null || nameFromData.isEmpty())
			return Response.status(Status.BAD_REQUEST).entity("Required data field '" + ProviderService.NAME + "' not set")
					.build();

		if (!id.equals(nameFromData)) {
			return Response.status(Status.BAD_REQUEST)
					.entity("Name in URL must be same as '" + ProviderService.NAME + "' field in data.").build();
		}

		try {
			// do not update password hash if entity exists already!
			Map<String, Object> entity = providerService.get(id);
			if (entity != null) {
				Object pwdhash = entity.get(ProviderService.PASSWORD_HASH);
				if (pwdhash != null)
					data.put(ProviderService.PASSWORD_HASH, pwdhash);
			}
			providerService.create(id, data);
			return createResponseWithId(id);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
	}

	@POST
	@Path("/{id}/password")
	@ProviderAllowed
	public Object changePassword(@PathParam("id") String id, String pwd) {

		if (id == null || id.isEmpty()) {
			return createRequiredFieldResponse("id");
		}

		if (pwd == null || pwd.trim().isEmpty()) {
			return createRequiredFieldResponse("pwd");
		}

		// check Provider name. Only provider with same name or superprovider has access.
		String provider = securityContext.getUserPrincipal().getName();

		Map<String, Object> entity = entityService.get(id);

		if (entity == null)
			return Response.status(Status.NOT_FOUND).build();

		String username = entity.get(ProviderService.NAME).toString();
		if (!provider.equals(username)) {
			if (!providerService.isSuperProvider(provider)) {
				return Response.status(Status.FORBIDDEN).build();
			}
		}
		entity.put(ProviderService.PASSWORD_HASH, securityService.createPwdHash(username, pwd.trim()));
		entityService.update(id, entity);

		return Response.ok().build();
	}

}
