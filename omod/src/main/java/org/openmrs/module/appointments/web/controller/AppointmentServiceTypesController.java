package org.openmrs.module.appointments.web.controller;

import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.service.AppointmentsServiceTypeService;
import org.openmrs.module.appointments.web.mapper.AppointmentServiceMapper;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.openmrs.api.context.Context;

@Controller
@RequestMapping(value = "/rest/" + RestConstants.VERSION_1 + "/appointmentServiceTypes")
public class AppointmentServiceTypesController extends BaseRestController {

    private Log log = LogFactory.getLog(this.getClass());

    @Autowired
    private AppointmentsServiceTypeService appointmentsServiceTypeService;

    @Autowired
    private AppointmentServiceMapper appointmentServiceMapper;

    // private AppointmentsServiceTypeService appointmentsServiceTypeService = Context.getService(AppointmentsServiceTypeService.class);

    /*
     * Returns all service types for a particular service given the service UUID
     * 
     * @param uuid - the service uuid
     * @return list of service types
     */
    @RequestMapping(method = RequestMethod.GET)
    @ResponseBody
    public List<Map<String, String>> getServiceTypesByServiceUuid(@RequestParam(value = "uuid") String uuid)  {
        List<AppointmentServiceType> serviceTypes = appointmentsServiceTypeService.getServiceTypesByServiceUuid(uuid);
        List<Map<String, String>> types = appointmentServiceMapper.constructResponseForServiceTypeList(serviceTypes);
        if(types == null) {
            log.error("Invalid. Service Types do not exist for sevice UUID - " + uuid);
            throw new RuntimeException("Service Types do not exist");
        }
        return types;
    }

}
