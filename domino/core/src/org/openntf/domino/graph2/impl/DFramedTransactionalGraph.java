package org.openntf.domino.graph2.impl;

import java.lang.reflect.Method;
import java.util.Map;

import javolution.util.FastMap;

import org.openntf.domino.big.impl.NoteCoordinate;
import org.openntf.domino.graph2.impl.DConfiguration.DTypeManager;
import org.openntf.domino.graph2.impl.DConfiguration.DTypeRegistry;
import org.openntf.domino.utils.DominoUtils;

import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.TransactionalGraph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.frames.EdgeFrame;
import com.tinkerpop.frames.FrameInitializer;
import com.tinkerpop.frames.FramedGraphConfiguration;
import com.tinkerpop.frames.FramedTransactionalGraph;
import com.tinkerpop.frames.VertexFrame;

public class DFramedTransactionalGraph<T extends TransactionalGraph> extends FramedTransactionalGraph<T> {

	public DFramedTransactionalGraph(final T baseGraph, final FramedGraphConfiguration config) {
		super(baseGraph, config);
	}

	public Map<String, Object> toJsonableMap(final VertexFrame frame) {
		Map<String, Object> result = new FastMap<String, Object>();
		result.put("id", frame.asVertex().getId());
		result.put("type", getTypeManager().resolve(frame).getName());
		Class<?>[] interfaces = frame.getClass().getInterfaces();
		if (interfaces.length > 0) {
			Map<String, Method> crystals = getTypeRegistry().getPropertiesGetters(interfaces);
			for (String key : crystals.keySet()) {
				Method crystal = crystals.get(key);
				if (crystal != null) {
					try {
						Object raw = crystal.invoke(frame, (Object[]) null);
						result.put(key, raw);
					} catch (Exception e) {
						e.printStackTrace();
					}
				} else {
					System.out.println("No method found for key " + key);
				}
			}
		}
		return result;
	}

	public Map<String, Object> toJsonableMap(final EdgeFrame frame) {
		Map<String, Object> result = new FastMap<String, Object>();
		Class<?>[] interfaces = frame.getClass().getInterfaces();
		if (interfaces.length > 0) {
			Map<String, Method> crystals = getTypeRegistry().getPropertiesGetters(interfaces);
			for (String key : crystals.keySet()) {
				Method crystal = crystals.get(key);
				if (crystal != null) {
					try {
						Object raw = crystal.invoke(frame, (Object[]) null);
						result.put(key, raw);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}
		return result;
	}

	protected DTypeRegistry getTypeRegistry() {
		Graph graph = this.getBaseGraph();
		if (graph instanceof DGraph) {
			DConfiguration config = (DConfiguration) ((DGraph) graph).getConfiguration();
			return config.getTypeRegistry();
		}
		return null;
	}

	protected DTypeManager getTypeManager() {
		Graph graph = this.getBaseGraph();
		if (graph instanceof DGraph) {
			DConfiguration config = (DConfiguration) ((DGraph) graph).getConfiguration();
			return config.getTypeManager();
		}
		return null;
	}

	private String getTypedId(final Object id) {
		String result = null;
		if (id != null && id instanceof String) {
			String idStr = (String) id;
			if (idStr.length() == 16) {
				if (DominoUtils.isReplicaId(idStr)) {
					result = idStr;
				}
			} else if (idStr.length() > 16) {
				if (idStr.length() == 32) {
					if (DominoUtils.isUnid(idStr)) {
						result = null;
					}
				} else {
					String prefix = idStr.substring(0, 16);
					if (DominoUtils.isReplicaId(prefix)) {
						result = prefix;
					}
				}
			}
		}
		return result;
	}

	@Override
	public <F> F addVertex(final Object id, final Class<F> kind) {
		DGraph base = (DGraph) this.getBaseGraph();
		org.openntf.domino.graph2.DElementStore store = null;
		if (id instanceof NoteCoordinate) {
			store = base.findElementStore(id);
		} else {
			String typeid = getTypedId(id);
			if (typeid == null) {
				store = base.findElementStore(kind);
			} else {
				store = base.findElementStore(typeid);
			}
		}
		Vertex vertex = store.addVertex(id);
		for (FrameInitializer initializer : getConfig().getFrameInitializers()) {
			initializer.initElement(kind, this, vertex);
		}
		return this.frame(vertex, kind);
	}

	@Override
	public <F> F getVertex(final Object id, final Class<F> kind) {
		DGraph base = (DGraph) this.getBaseGraph();
		org.openntf.domino.graph2.DElementStore store = null;
		if (id instanceof NoteCoordinate) {
			store = base.findElementStore(id);
		} else {
			String typeid = getTypedId(id);
			if (typeid == null) {
				store = base.findElementStore(kind);
			} else {
				store = base.findElementStore(typeid);
			}
		}
		Vertex vertex = store.getVertex(id);
		for (FrameInitializer initializer : getConfig().getFrameInitializers()) {
			initializer.initElement(kind, this, vertex);
		}
		return this.frame(vertex, kind);
	}

	@Override
	public <F> Iterable<F> getVertices(final String key, final Object value, final Class<F> kind) {
		org.openntf.domino.graph2.DElementStore store = null;
		DGraph base = (DGraph) this.getBaseGraph();
		store = base.findElementStore(kind);
		if (store != null) {
			String formulaFilter = org.openntf.domino.graph2.DGraph.Utils.getFramedVertexFormula(key, value, kind);
			Iterable<Vertex> vertices = store.getVertices(formulaFilter);
			return this.frameVertices(vertices, kind);
		} else {
			return null;
		}
	}

	@Override
	public <F> F addEdge(final Object id, final Vertex outVertex, final Vertex inVertex, final String label, final Direction direction,
			final Class<F> kind) {
		DGraph base = (DGraph) this.getBaseGraph();
		org.openntf.domino.graph2.DElementStore store = null;
		if (id instanceof NoteCoordinate) {
			store = base.findElementStore(id);
		} else {
			String typeid = getTypedId(id);
			if (typeid == null) {
				store = base.findElementStore(kind);
			} else {
				store = base.findElementStore(typeid);
			}
		}
		Edge edge = store.addEdge(id);
		((DEdge) edge).setLabel(label);
		((DEdge) edge).setInVertex(inVertex);
		((DEdge) edge).setOutVertex(outVertex);
		for (FrameInitializer initializer : getConfig().getFrameInitializers()) {
			initializer.initElement(kind, this, edge);
		}
		return this.frame(edge, kind);
	}

	@Override
	public <F> Iterable<F> getEdges(final String key, final Object value, final Class<F> kind) {
		org.openntf.domino.graph2.DElementStore store = null;
		DGraph base = (DGraph) this.getBaseGraph();
		store = base.findElementStore(kind);
		if (store != null) {
			String formulaFilter = org.openntf.domino.graph2.DGraph.Utils.getFramedEdgeFormula(key, value, kind);
			Iterable<Edge> edges = store.getEdges(formulaFilter);
			return this.frameEdges(edges, kind);
		} else {
			return null;
		}
	}

}
