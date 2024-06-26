/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.rest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.List;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.rest.model.CellSetModel;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.javax.ws.rs.Encoded;
import org.apache.hbase.thirdparty.javax.ws.rs.GET;
import org.apache.hbase.thirdparty.javax.ws.rs.HeaderParam;
import org.apache.hbase.thirdparty.javax.ws.rs.Produces;
import org.apache.hbase.thirdparty.javax.ws.rs.QueryParam;
import org.apache.hbase.thirdparty.javax.ws.rs.core.Context;
import org.apache.hbase.thirdparty.javax.ws.rs.core.MultivaluedMap;
import org.apache.hbase.thirdparty.javax.ws.rs.core.Response;
import org.apache.hbase.thirdparty.javax.ws.rs.core.UriInfo;

@InterfaceAudience.Private
public class MultiRowResource extends ResourceBase implements Constants {
  private static final Logger LOG = LoggerFactory.getLogger(MultiRowResource.class);

  private static final Decoder base64Urldecoder = Base64.getUrlDecoder();

  TableResource tableResource;
  Integer versions = null;
  String[] columns = null;

  /**
   * Constructor
   */
  public MultiRowResource(TableResource tableResource, String versions, String columnsStr)
    throws IOException {
    super();
    this.tableResource = tableResource;

    if (columnsStr != null && !columnsStr.equals("")) {
      this.columns = columnsStr.split(",");
    }

    if (versions != null) {
      this.versions = Integer.valueOf(versions);

    }
  }

  @GET
  @Produces({ MIMETYPE_XML, MIMETYPE_JSON, MIMETYPE_PROTOBUF, MIMETYPE_PROTOBUF_IETF })
  public Response get(final @Context UriInfo uriInfo,
    final @HeaderParam("Encoding") String keyEncodingHeader,
    @QueryParam(Constants.FILTER_B64) @Encoded String paramFilterB64,
    @QueryParam(Constants.FILTER) String paramFilter) {
    MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
    String keyEncoding = (keyEncodingHeader != null)
      ? keyEncodingHeader
      : params.getFirst(KEY_ENCODING_QUERY_PARAM_NAME);

    servlet.getMetrics().incrementRequests(1);

    byte[] filterBytes = null;
    if (paramFilterB64 != null) {
      filterBytes = base64Urldecoder.decode(paramFilterB64);
    } else if (paramFilter != null) {
      filterBytes = paramFilter.getBytes();
    }

    try {
      Filter parsedParamFilter = null;
      if (filterBytes != null) {
        // Note that this is a completely different representation of the filters
        // than the JSON one used in the /table/scanner endpoint
        ParseFilter pf = new ParseFilter();
        parsedParamFilter = pf.parseFilterString(filterBytes);
      }
      List<RowSpec> rowSpecs = new ArrayList<>();
      for (String rk : params.get(ROW_KEYS_PARAM_NAME)) {
        RowSpec rowSpec = new RowSpec(rk, keyEncoding);

        if (this.versions != null) {
          rowSpec.setMaxVersions(this.versions);
        }

        if (this.columns != null) {
          for (int i = 0; i < this.columns.length; i++) {
            rowSpec.addColumn(Bytes.toBytes(this.columns[i]));
          }
        }
        rowSpecs.add(rowSpec);
      }

      MultiRowResultReader reader = new MultiRowResultReader(this.tableResource.getName(), rowSpecs,
        parsedParamFilter, !params.containsKey(NOCACHE_PARAM_NAME));

      CellSetModel model = new CellSetModel();
      for (Result r : reader.getResults()) {
        if (r.isEmpty()) {
          continue;
        }
        model.addRow(RestUtil.createRowModelFromResult(r));
      }
      if (model.getRows().isEmpty()) {
        // If no rows found.
        servlet.getMetrics().incrementFailedGetRequests(1);
        return Response.status(Response.Status.NOT_FOUND).type(MIMETYPE_TEXT)
          .entity("No rows found." + CRLF).build();
      } else {
        servlet.getMetrics().incrementSucessfulGetRequests(1);
        return Response.ok(model).build();
      }
    } catch (IOException e) {
      servlet.getMetrics().incrementFailedGetRequests(1);
      return processException(e);
    }
  }
}
