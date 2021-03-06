/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.dcp.api.rest;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.jboss.dcp.api.DcpContentObjectFields;
import org.jboss.dcp.api.service.ProviderService;
import org.jboss.dcp.api.testtools.ESRealClientTestBase;
import org.jboss.dcp.api.testtools.TestUtils;
import org.jboss.dcp.persistence.service.ContentPersistenceService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit test for {@link ContentRestService}
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public class ContentRestServiceTest extends ESRealClientTestBase {

	private static final String INDEX_TYPE = "index_type";
	private static final String INDEX_NAME = "index_name";
	private static final List<Map<String, Object>> PREPROCESSORS = new ArrayList<Map<String, Object>>();

	@Test
	public void pushContent_permissions() throws Exception {
		TestUtils.assertPermissionProvider(ContentRestService.class, "pushContent", String.class, String.class, Map.class);
	}

	@Test
	public void pushContent_invalidParams() throws Exception {
		ContentRestService tested = getTested(false);

		Map<String, Object> content = new HashMap<String, Object>();
		content.put("test", "test");
		// case - invalid input parameters
		Mockito.reset(tested.contentPersistenceService);
		TestUtils.assertResponseStatus(tested.pushContent(null, "1", content), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.pushContent("", "1", content), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.pushContent("known", null, content), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.pushContent("known", "", content), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.pushContent("known", "1", null), Response.Status.BAD_REQUEST);
		content.clear();
		TestUtils.assertResponseStatus(tested.pushContent("known", "1", content), Response.Status.BAD_REQUEST);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - type is unknown
		Mockito.reset(tested.contentPersistenceService);
		content.put("test", "test");
		TestUtils.assertResponseStatus(tested.pushContent("unknown", "1", content), Response.Status.BAD_REQUEST);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - type configuration is invalid - do not contains index name and/or index type
		Mockito.reset(tested.contentPersistenceService);
		TestUtils.assertResponseStatus(tested.pushContent("invalid", "1", content), Response.Status.INTERNAL_SERVER_ERROR);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - type configuration is invalid - do not contains dcp_type definition
		Mockito.reset(tested.contentPersistenceService);
		TestUtils
				.assertResponseStatus(tested.pushContent("invalid_2", "1", content), Response.Status.INTERNAL_SERVER_ERROR);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - dcp_content_content-type not defined if input value contains dcp_content
		Mockito.reset(tested.contentPersistenceService);
		content.put(DcpContentObjectFields.DCP_CONTENT, "some content");
		TestUtils.assertResponseStatus(tested.pushContent("invalid-content-type", "1", content),
				Response.Status.INTERNAL_SERVER_ERROR);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

	}

	@SuppressWarnings("unchecked")
	@Test
	public void pushContent_noPersistence() throws Exception {
		try {
			ContentRestService tested = getTested(true);
			Map<String, Object> content = new HashMap<String, Object>();

			// case - insert when index is not found
			String dcp_content_type = "known";
			{
				Mockito.reset(tested.contentPersistenceService);
				indexDelete(INDEX_NAME);
				content.clear();
				content.put("test", "testvalue");
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "1", content),
						Response.Status.OK);
				Assert.assertEquals("insert", ((Map<String, String>) r.getEntity()).get("status"));
				Mockito.verify(tested.providerService).runPreprocessors(dcp_content_type, PREPROCESSORS, content);
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "1"));
				Assert.assertNotNull(doc);
				Assert.assertEquals("testvalue", doc.get("test"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("1", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "1"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_TAGS));
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - insert when index is found, fill dcp_updated if not provided in content, process tags provided in
			// content, fill dcp_content_content-type because dcp_content is present
			{
				Mockito.reset(tested.providerService, tested.contentPersistenceService);
				setupProviderServiceMock(tested);
				content.put("test2", "testvalue2");
				content.put(DcpContentObjectFields.DCP_CONTENT, "dcp content");
				content.remove(DcpContentObjectFields.DCP_UPDATED);
				String[] tags = new String[] { "tag_value" };
				content.put("tags", tags);
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "2", content),
						Response.Status.OK);
				Assert.assertEquals("insert", ((Map<String, String>) r.getEntity()).get("status"));
				Mockito.verify(tested.providerService).runPreprocessors(dcp_content_type, PREPROCESSORS, content);
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "2"));
				Assert.assertNotNull(doc);
				Assert.assertEquals("testvalue", doc.get("test"));
				Assert.assertEquals("testvalue2", doc.get("test2"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("2", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals("text/plain", doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "2"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals("tag_value", ((List<String>) doc.get(DcpContentObjectFields.DCP_TAGS)).get(0));
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - rewrite document in index
			{
				Mockito.reset(tested.providerService, tested.contentPersistenceService);
				setupProviderServiceMock(tested);
				content.clear();
				content.put("test3", "testvalue3");
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "1", content),
						Response.Status.OK);
				Assert.assertEquals("update", ((Map<String, String>) r.getEntity()).get("status"));
				Mockito.verify(tested.providerService).runPreprocessors(dcp_content_type, PREPROCESSORS, content);
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "1"));
				Assert.assertNotNull(doc);
				Assert.assertEquals(null, doc.get("test"));
				Assert.assertEquals("testvalue3", doc.get("test3"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("1", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "1"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_TAGS));
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}
		} finally {
			indexDelete(INDEX_NAME);
			finalizeESClientForUnitTest();
		}
	}

	@SuppressWarnings("unchecked")
	@Test
	public void pushContent_persistence() throws Exception {
		try {
			ContentRestService tested = getTested(true);
			Map<String, Object> content = new HashMap<String, Object>();

			// case - insert when index is not found, remove dcp_content_content-type because dcp_content not present
			String dcp_content_type = "persist";
			{
				Mockito.reset(tested.contentPersistenceService);
				indexDelete(INDEX_NAME);
				content.clear();
				content.put("test", "testvalue");
				content.put(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE, "text/html");
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "1", content),
						Response.Status.OK);
				Assert.assertEquals("insert", ((Map<String, String>) r.getEntity()).get("status"));
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "1"));
				Assert.assertNotNull(doc);
				Assert.assertEquals("testvalue", doc.get("test"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("1", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "1"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_TAGS));
				Mockito.verify(tested.contentPersistenceService).store(
						tested.providerService.generateDcpId(dcp_content_type, "1"), dcp_content_type, content);
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - insert when index is found, fill dcp_updated if not provided in content, process tags provided in
			// content, rewrite dcp_content_content-type because dcp_content is present
			{
				Mockito.reset(tested.providerService, tested.contentPersistenceService);
				setupProviderServiceMock(tested);
				content.put("test2", "testvalue2");
				content.put(DcpContentObjectFields.DCP_CONTENT, "dcp content");
				content.put(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE, "text/html");
				content.remove(DcpContentObjectFields.DCP_UPDATED);
				String[] tags = new String[] { "tag_value" };
				content.put("tags", tags);
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "2", content),
						Response.Status.OK);
				Assert.assertEquals("insert", ((Map<String, String>) r.getEntity()).get("status"));
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "2"));
				Assert.assertNotNull(doc);
				Assert.assertEquals("testvalue", doc.get("test"));
				Assert.assertEquals("testvalue2", doc.get("test2"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("2", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals("text/plain", doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "2"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals("tag_value", ((List<String>) doc.get(DcpContentObjectFields.DCP_TAGS)).get(0));
				Mockito.verify(tested.contentPersistenceService).store(
						tested.providerService.generateDcpId(dcp_content_type, "2"), dcp_content_type, content);
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - rewrite document in index
			{
				Mockito.reset(tested.providerService, tested.contentPersistenceService);
				setupProviderServiceMock(tested);
				content.clear();
				content.put("test3", "testvalue3");
				Response r = TestUtils.assertResponseStatus(tested.pushContent(dcp_content_type, "1", content),
						Response.Status.OK);
				Assert.assertEquals("update", ((Map<String, String>) r.getEntity()).get("status"));
				indexFlush(INDEX_NAME);
				Map<String, Object> doc = indexGetDocument(INDEX_NAME, INDEX_TYPE,
						tested.providerService.generateDcpId(dcp_content_type, "1"));
				Assert.assertNotNull(doc);
				Assert.assertEquals(null, doc.get("test"));
				Assert.assertEquals("testvalue3", doc.get("test3"));
				Assert.assertEquals("jbossorg", doc.get(DcpContentObjectFields.DCP_CONTENT_PROVIDER));
				Assert.assertEquals("1", doc.get(DcpContentObjectFields.DCP_CONTENT_ID));
				Assert.assertEquals(dcp_content_type, doc.get(DcpContentObjectFields.DCP_CONTENT_TYPE));
				Assert.assertEquals("my_dcp_type", doc.get(DcpContentObjectFields.DCP_TYPE));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_CONTENT_CONTENT_TYPE));
				Assert.assertEquals(tested.providerService.generateDcpId(dcp_content_type, "1"),
						doc.get(DcpContentObjectFields.DCP_ID));
				Assert.assertNotNull(doc.get(DcpContentObjectFields.DCP_UPDATED));
				Assert.assertEquals(null, doc.get(DcpContentObjectFields.DCP_TAGS));
				Mockito.verify(tested.contentPersistenceService).store(
						tested.providerService.generateDcpId(dcp_content_type, "1"), dcp_content_type, content);
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}
		} finally {
			indexDelete(INDEX_NAME);
			finalizeESClientForUnitTest();
		}
	}

	@Test
	public void deleteContent_permissions() throws Exception {
		TestUtils.assertPermissionProvider(ContentRestService.class, "deleteContent", String.class, String.class,
				String.class);
	}

	@Test
	public void deleteContent() throws Exception {
		ContentRestService tested = getTested(false);

		// case - invalid input parameters
		TestUtils.assertResponseStatus(tested.deleteContent(null, "1", null), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.deleteContent("", "1", null), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.deleteContent("known", null, null), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.deleteContent("known", "", null), Response.Status.BAD_REQUEST);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - type is unknown
		TestUtils.assertResponseStatus(tested.deleteContent("unknown", "1", null), Response.Status.BAD_REQUEST);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		// case - type configuration is invalid - do not contains index name and/or index type
		TestUtils.assertResponseStatus(tested.deleteContent("invalid", "1", null), Response.Status.INTERNAL_SERVER_ERROR);
		Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

		try {
			tested = getTested(true);

			// case - delete when index is not found
			{
				Mockito.reset(tested.contentPersistenceService);
				indexDelete(INDEX_NAME);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", null), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("persist", "1", null), Response.Status.NOT_FOUND);
				Mockito.verify(tested.contentPersistenceService).delete(tested.providerService.generateDcpId("persist", "1"),
						"persist");
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - delete when document doesn't exist in index
			{
				Mockito.reset(tested.contentPersistenceService);
				indexDelete(INDEX_NAME);
				indexCreate(INDEX_NAME);
				indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-2", "{\"test2\":\"test2\"}");
				indexFlush(INDEX_NAME);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", null), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("persist", "1", null), Response.Status.NOT_FOUND);
				Assert.assertNotNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "known-2"));
				Mockito.verify(tested.contentPersistenceService).delete(tested.providerService.generateDcpId("persist", "1"),
						"persist");
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);
			}

			// case - delete when document exist in index
			{
				Mockito.reset(tested.contentPersistenceService);
				indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-1", "{\"test1\":\"test1\"}");
				indexInsertDocument(INDEX_NAME, INDEX_TYPE, "persist-1", "{\"test1\":\"testper1\"}");
				indexFlush(INDEX_NAME);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", null), Response.Status.OK);
				Assert.assertNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "known-1"));
				TestUtils.assertResponseStatus(tested.deleteContent("persist", "1", null), Response.Status.OK);
				Assert.assertNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "persist-1"));
				Assert.assertNotNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "known-2"));
				// another subsequent deletes
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", null), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("persist", "1", null), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "2", null), Response.Status.OK);
				Assert.assertNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "known-1"));
				Assert.assertNull(indexGetDocument(INDEX_NAME, INDEX_TYPE, "known-2"));

				// test ignore_missing parameter
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", null), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", "false"), Response.Status.NOT_FOUND);
				TestUtils.assertResponseStatus(tested.deleteContent("known", "1", "true"), Response.Status.OK);

				Mockito.verify(tested.contentPersistenceService, Mockito.times(2)).delete(
						tested.providerService.generateDcpId("persist", "1"), "persist");
				Mockito.verifyNoMoreInteractions(tested.contentPersistenceService);

			}

		} finally {
			indexDelete(INDEX_NAME);
			finalizeESClientForUnitTest();
		}
	}

	@Test
	public void getAllContent_permissions() throws IOException, InterruptedException {
		TestUtils.assertPermissionGuest(ContentRestService.class, "getAllContent", String.class, Integer.class,
				Integer.class, String.class);
	}

	@Test
	public void getAllContent() throws IOException, InterruptedException {
		ContentRestService tested = getTested(false);

		// case - invalid input parameters
		TestUtils.assertResponseStatus(tested.getAllContent(null, null, null, null), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.getAllContent("", null, null, null), Response.Status.BAD_REQUEST);

		// case - type is unknown
		TestUtils.assertResponseStatus(tested.getAllContent("unknown", null, null, null), Response.Status.BAD_REQUEST);

		// case - type configuration is invalid (do not contains index name and type)
		TestUtils.assertResponseStatus(tested.getAllContent("invalid", null, null, null),
				Response.Status.INTERNAL_SERVER_ERROR);

		try {
			tested = getTested(true);

			// case - type is valid, but nothing is found because index is missing
			indexDelete(INDEX_NAME);
			TestUtils.assertResponseStatus(tested.getAllContent("known", null, null, null), Response.Status.NOT_FOUND);

			// case - nothing found because index is empty
			indexCreate(INDEX_NAME);
			TestUtils.assetStreamingOutputContent("{\"total\":0,\"hits\":[]}",
					tested.getAllContent("known", null, null, null));

			// case - something found, no from and size param used
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-1",
					"{\"name\":\"test1\", \"dcp_updated\" : 0,\"dcp_content_id\":\"1\"}");
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-2",
					"{\"name\":\"test2\",\"dcp_updated\":0,\"dcp_content_id\":\"2\"}");
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-3",
					"{\"name\":\"test3\",\"dcp_updated\":0,\"dcp_content_id\":\"3\"}");
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-4",
					"{\"name\":\"test4\",\"dcp_updated\":0,\"dcp_content_id\":\"4\"}");
			indexFlush(INDEX_NAME);
			TestUtils
					.assetStreamingOutputContent(
							"{\"total\":4,\"hits\":[{\"id\":\"1\",\"data\":{\"name\":\"test1\",\"dcp_content_id\":\"1\",\"dcp_updated\":0}},{\"id\":\"2\",\"data\":{\"name\":\"test2\",\"dcp_content_id\":\"2\",\"dcp_updated\":0}},{\"id\":\"3\",\"data\":{\"name\":\"test3\",\"dcp_content_id\":\"3\",\"dcp_updated\":0}},{\"id\":\"4\",\"data\":{\"name\":\"test4\",\"dcp_content_id\":\"4\",\"dcp_updated\":0}}]}",
							tested.getAllContent("known", null, null, null));

			// case - something found, from and size param used
			TestUtils
					.assetStreamingOutputContent(
							"{\"total\":4,\"hits\":[{\"id\":\"2\",\"data\":{\"name\":\"test2\",\"dcp_content_id\":\"2\",\"dcp_updated\":0}},{\"id\":\"3\",\"data\":{\"name\":\"test3\",\"dcp_content_id\":\"3\",\"dcp_updated\":0}}]}",
							tested.getAllContent("known", 1, 2, null));

			// case - sort param used
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-5",
					"{\"name\":\"test5\", \"dcp_updated\" : 1,\"dcp_content_id\":\"5\"}");
			indexFlush(INDEX_NAME);
			// on ASC our record with id 5 is last, so we set from=4
			TestUtils
					.assetStreamingOutputContent(
							"{\"total\":5,\"hits\":[{\"id\":\"5\",\"data\":{\"name\":\"test5\",\"dcp_content_id\":\"5\",\"dcp_updated\":1}}]}",
							tested.getAllContent("known", 4, 1, "asc"));
			// on DESC our record with id 5 is first, so we set from=0
			TestUtils
					.assetStreamingOutputContent(
							"{\"total\":5,\"hits\":[{\"id\":\"5\",\"data\":{\"name\":\"test5\",\"dcp_content_id\":\"5\",\"dcp_updated\":1}}]}",
							tested.getAllContent("known", 0, 1, "DESC"));

		} finally {
			indexDelete(INDEX_NAME);
			finalizeESClientForUnitTest();
		}
	}

	@Test
	public void getContent_permissions() {
		TestUtils.assertPermissionGuest(ContentRestService.class, "getContent", String.class, String.class);
	}

	@SuppressWarnings("unchecked")
	@Test
	public void getContent() {
		ContentRestService tested = getTested(false);

		// case - invalid input parameters
		TestUtils.assertResponseStatus(tested.getContent(null, "1"), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.getContent("", "1"), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.getContent("testtype", null), Response.Status.BAD_REQUEST);
		TestUtils.assertResponseStatus(tested.getContent("testtype", ""), Response.Status.BAD_REQUEST);

		// case - type is unknown
		TestUtils.assertResponseStatus(tested.getContent("unknown", "12"), Response.Status.BAD_REQUEST);

		// case - type configuration is invalid (do not contains index name and type)
		TestUtils.assertResponseStatus(tested.getContent("invalid", "12"), Response.Status.INTERNAL_SERVER_ERROR);

		try {
			tested = getTested(true);

			// case - type is valid, but nothing is found for passed id because index is missing
			indexDelete(INDEX_NAME);
			TestUtils.assertResponseStatus(tested.getContent("known", "12"), Response.Status.NOT_FOUND);

			// case - index is present bud document is not found
			indexCreate(INDEX_NAME);
			indexInsertDocument(INDEX_NAME, INDEX_TYPE, "known-1", "{\"name\":\"test\"}");
			TestUtils.assertResponseStatus(tested.getContent("known", "2"), Response.Status.NOT_FOUND);

			// case - document found
			Map<String, Object> ret = (Map<String, Object>) tested.getContent("known", "1");
			Assert.assertEquals("test", ret.get("name"));

		} finally {
			indexDelete(INDEX_NAME);
			finalizeESClientForUnitTest();
		}
	}

	/**
	 * Prepare tested instance with injected mocks.
	 * 
	 * @param initEsClient - searchClientService is initialized from
	 *          {@link ESRealClientTestBase#prepareSearchClientServiceMock()} if this is true, so do not forget to clean
	 *          up client in finally!
	 * @return instance for test
	 */
	protected ContentRestService getTested(boolean initEsClient) {
		ContentRestService tested = new ContentRestService();
		if (initEsClient)
			tested.searchClientService = prepareSearchClientServiceMock();

		tested.providerService = Mockito.mock(ProviderService.class);
		setupProviderServiceMock(tested);

		tested.contentPersistenceService = Mockito.mock(ContentPersistenceService.class);
		tested.log = Logger.getLogger("testlogger");
		tested.securityContext = Mockito.mock(SecurityContext.class);
		Mockito.when(tested.securityContext.getUserPrincipal()).thenReturn(new Principal() {

			@Override
			public String getName() {
				return "jbossorg";
			}
		});
		return tested;
	}

	/**
	 * @param tested
	 */
	protected void setupProviderServiceMock(ContentRestService tested) {
		Mockito.when(tested.providerService.findContentType("invalid")).thenReturn(new HashMap<String, Object>());
		Mockito.when(tested.providerService.findContentType("unknown")).thenReturn(null);
		Mockito.when(tested.providerService.generateDcpId(Mockito.anyString(), Mockito.anyString())).thenCallRealMethod();

		Map<String, Object> typeDefKnown = new HashMap<String, Object>();
		Map<String, Object> typeDefKnownIndex = new HashMap<String, Object>();
		typeDefKnown.put(ProviderService.INDEX, typeDefKnownIndex);
		typeDefKnownIndex.put("name", INDEX_NAME);
		typeDefKnownIndex.put("type", INDEX_TYPE);
		typeDefKnown.put("input_preprocessors", PREPROCESSORS);
		typeDefKnown.put(ProviderService.DCP_TYPE, "my_dcp_type");
		typeDefKnown.put(ProviderService.DCP_CONTENT_CONTENT_TYPE, "text/plain");
		Mockito.when(tested.providerService.findContentType("known")).thenReturn(typeDefKnown);

		Map<String, Object> typeDefPersist = new HashMap<String, Object>();
		Map<String, Object> typeDefPersistIndex = new HashMap<String, Object>();
		typeDefPersist.put(ProviderService.INDEX, typeDefPersistIndex);
		typeDefPersistIndex.put("name", INDEX_NAME);
		typeDefPersistIndex.put("type", INDEX_TYPE);
		typeDefPersist.put(ProviderService.DCP_TYPE, "my_dcp_type");
		typeDefPersist.put(ProviderService.PERSIST, "true");
		typeDefPersist.put(ProviderService.DCP_CONTENT_CONTENT_TYPE, "text/plain");
		Mockito.when(tested.providerService.findContentType("persist")).thenReturn(typeDefPersist);

		Map<String, Object> typeDefDcpContent = new HashMap<String, Object>();
		Map<String, Object> typeDefDcpContentIndex = new HashMap<String, Object>();
		typeDefDcpContent.put(ProviderService.INDEX, typeDefPersistIndex);
		typeDefDcpContentIndex.put("name", INDEX_NAME);
		typeDefDcpContentIndex.put("type", INDEX_TYPE);
		typeDefDcpContent.put(ProviderService.DCP_TYPE, "my_dcp_type");
		Mockito.when(tested.providerService.findContentType("invalid-content-type")).thenReturn(typeDefDcpContent);

		Map<String, Object> providerDef = new HashMap<String, Object>();
		Mockito.when(tested.providerService.findProvider("jbossorg")).thenReturn(providerDef);
		Map<String, Object> typesDef = new HashMap<String, Object>();
		providerDef.put(ProviderService.TYPE, typesDef);
		typesDef.put("invalid", new HashMap<String, Object>());
		Map<String, Object> typeDefInvalid2 = new HashMap<String, Object>();
		typeDefInvalid2.put(ProviderService.INDEX, typeDefKnownIndex);
		typesDef.put("invalid_2", typeDefInvalid2);
		typesDef.put("known", typeDefKnown);
		typesDef.put("persist", typeDefPersist);
		typesDef.put("invalid-content-type", typeDefDcpContent);
	}
}
