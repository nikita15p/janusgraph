// Copyright 2021 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.graphdb.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.apache.tinkerpop.gremlin.server.GraphManager;
import org.apache.tinkerpop.gremlin.server.Settings;
import org.apache.tinkerpop.gremlin.server.util.DefaultGraphManager;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.schema.EdgeLabelMaker;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.PropertyKeyMaker;
import org.janusgraph.core.schema.VertexLabelMaker;
import org.janusgraph.graphdb.grpc.schema.SchemaManagerImpl;
import org.janusgraph.graphdb.grpc.schema.util.GrpcUtils;
import org.janusgraph.graphdb.grpc.types.EdgeLabel;
import org.janusgraph.graphdb.grpc.types.EdgeLabelOrBuilder;
import org.janusgraph.graphdb.grpc.types.EdgeProperty;
import org.janusgraph.graphdb.grpc.types.VertexLabelOrBuilder;
import org.janusgraph.graphdb.grpc.types.VertexProperty;
import org.janusgraph.graphdb.server.TestingServerClosable;
import org.javatuples.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.util.HashMap;

public abstract class JanusGraphGrpcServerBaseTest {

    protected JanusGraphContextHandler contextHandler;
    protected GraphManager graphManager;
    protected ManagedChannel managedChannel;
    private TestingServerClosable closable;

    protected static GraphManager getGraphManager() {
        Settings settings = new Settings();
        HashMap<String, String> map = new HashMap<>();
        map.put("graph", "src/test/resources/janusgraph-inmemory.properties");
        map.put("graph2", "src/test/resources/janusgraph-inmemory.properties");
        map.put("tinkergraph", "src/test/resources/tinkergraph.properties");
        settings.graphs = map;
        return new DefaultGraphManager(settings);
    }

    public long createVertexLabel(String graph, VertexLabelOrBuilder builder) {
        JanusGraphManagement management = ((JanusGraph) graphManager.getGraph(graph)).openManagement();
        VertexLabelMaker vertexLabelMaker = management.makeVertexLabel(builder.getName());
        if (builder.getReadOnly()) {
            vertexLabelMaker.setStatic();
        }
        if (builder.getPartitioned()) {
            vertexLabelMaker.partition();
        }
        VertexLabel vertexLabel = vertexLabelMaker.make();
        for (VertexProperty vertexProperty : builder.getPropertiesList()) {
            PropertyKeyMaker propertyKeyMaker = management.makePropertyKey(vertexProperty.getName());
            PropertyKey propertyKey = propertyKeyMaker
                .cardinality(GrpcUtils.convertGrpcCardinality(vertexProperty.getCardinality()))
                .dataType(GrpcUtils.convertGrpcPropertyDataType(vertexProperty.getDataType()))
                .make();
            management.addProperties(vertexLabel, propertyKey);
        }

        management.commit();
        return vertexLabel.longId();
    }

    public long createEdgeLabel(String graph, EdgeLabelOrBuilder builder) {
        JanusGraphManagement management = ((JanusGraph) graphManager.getGraph(graph)).openManagement();
        EdgeLabelMaker edgeLabelMaker = management.makeEdgeLabel(builder.getName());
        if (builder.getDirection() == EdgeLabel.Direction.DIRECTION_OUT) {
            edgeLabelMaker.unidirected();
        } else {
            edgeLabelMaker.directed();
        }
        edgeLabelMaker.multiplicity(GrpcUtils.convertGrpcEdgeMultiplicity(builder.getMultiplicity()));
        org.janusgraph.core.EdgeLabel edgeLabel = edgeLabelMaker.make();
        for (EdgeProperty edgeProperty : builder.getPropertiesList()) {
            PropertyKeyMaker propertyKeyMaker = management.makePropertyKey(edgeProperty.getName());
            PropertyKey propertyKey = propertyKeyMaker
                .dataType(GrpcUtils.convertGrpcPropertyDataType(edgeProperty.getDataType()))
                .make();
            management.addProperties(edgeLabel, propertyKey);
        }

        management.commit();
        return edgeLabel.longId();
    }

    private static Pair<Server, String> createServer(JanusGraphContextHandler contextHandler) throws IOException {
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(new JanusGraphManagerServiceImpl(contextHandler))
            .addService(new SchemaManagerImpl(contextHandler))
            .build().start();
        return new Pair<>(server, serverName);
    }

    @BeforeEach
    public void startServer() throws IOException {
        graphManager = getGraphManager();
        contextHandler = new JanusGraphContextHandler(graphManager);
        Pair<Server, String> server = createServer(contextHandler);
        managedChannel = InProcessChannelBuilder.forName(server.getValue1()).directExecutor().build();
        closable = new TestingServerClosable(server.getValue0(), managedChannel);
    }

    @AfterEach
    public void stopServer() throws IOException {
        closable.close();
    }
}
