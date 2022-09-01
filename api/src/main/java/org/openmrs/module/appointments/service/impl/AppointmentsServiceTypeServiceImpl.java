package org.openmrs.module.appointments.service.impl;

import java.util.List;

import org.openmrs.module.appointments.dao.AppointmentServiceDao;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.service.AppointmentsServiceTypeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// @Service
public class AppointmentsServiceTypeServiceImpl implements AppointmentsServiceTypeService {

    private AppointmentServiceDao appointmentServiceDao;

    public void setAppointmentServiceDao(AppointmentServiceDao appointmentServiceDao) {
        this.appointmentServiceDao = appointmentServiceDao;
    }

    @Transactional
    @Override
    public List<AppointmentServiceType> getServiceTypesByServiceUuid(String uuid) {
        List<AppointmentServiceType> serviceTypes = appointmentServiceDao.getServiceTypesByServiceUuid(uuid);
        return serviceTypes;
    }

}
