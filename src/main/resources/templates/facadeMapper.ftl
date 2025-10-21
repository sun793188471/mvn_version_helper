package ${facadeMapperPackageName};

import org.mapstruct.Mapper;
${customAllDtoImport}
${customAllVoImport}



/**
* ${facadeMapperClassName}.java desc
* @author ${author}
* @version name: ${facadeMapperClassName}.java, v1.0 ${date}  ${author}
*/
@Mapper(componentModel = "spring")
public interface ${facadeMapperClassName} {
    /**
    * dto 2 vo
    * @param dto
    * @return
    */
    ${requestVoClassName} dto2vo(${requestDtoClassName} dto);

    /**
    * vo 2 dto
    * @param vo
    * @return
    */
    ${responseDtoClassName} vo2dto(${responseVoClassName} vo);
}