package org.openntf.domino.rest.resources.frames;

import javax.ws.rs.Path;

import org.openntf.domino.rest.service.ODAGraphService;
import org.openntf.domino.rest.service.Routes;

@Path(Routes.FRAMED + "/" + Routes.NAMESPACE_PATH_PARAM + "/" + Routes.VERTEXES)
public class VertexFrameCollectionResource extends FramedCollectionResource {

	public VertexFrameCollectionResource(ODAGraphService service) {
		super(service);
		// TODO Auto-generated constructor stub
	}

}
