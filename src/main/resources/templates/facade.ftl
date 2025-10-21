package ${facadePackageName};

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import com.ly.sof.facade.base.BaseRequestDTO;
import com.ly.spat.dsf.annoation.RequestBody;
${customAllDtoImport}

/**
* ${facadeInterfaceClassName}.java desc
* @author ${author}
* @version name: ${facadeInterfaceClassName}.java, v1.0 ${date}  ${author}
*/
@Path("${path}")
public interface ${facadeInterfaceClassName} {

    @POST
    @Consumes({ "application/json; charset=UTF-8" })
    @Produces({ "application/json; charset=UTF-8" })
    @Path("${methodName}")
    ${responseDtoClassName} ${methodName}(@RequestBody ${requestDtoClassName} requestDTO);

}
