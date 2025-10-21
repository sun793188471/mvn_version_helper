package ${packageName}.biz.mapper;
import java.util.List;

import org.mapstruct.Mapper;



/**
* Mapper for converting DO to VO and vice versa.
*/
@Mapper(componentModel = "spring")
public interface ${mapperClassName} {
/**
* Convert a list of DOs to a list of VOs.
*
* @param doList List of DOs
* @return List of VOs
*/
List<${voClassName}> dos2vos(List<${doClassName}> doList);
}