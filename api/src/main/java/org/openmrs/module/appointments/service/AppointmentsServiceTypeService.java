package org.openmrs.module.appointments.service;


import static org.openmrs.module.appointments.constants.PrivilegeConstants.MANAGE_APPOINTMENTS;
import static org.openmrs.module.appointments.constants.PrivilegeConstants.VIEW_APPOINTMENTS;

import java.util.List;

import org.openmrs.annotation.Authorized;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// @Service
public interface AppointmentsServiceTypeService {

    @Transactional
    @Authorized({VIEW_APPOINTMENTS, MANAGE_APPOINTMENTS})
    List<AppointmentServiceType> getServiceTypesByServiceUuid(String uuid);

}

