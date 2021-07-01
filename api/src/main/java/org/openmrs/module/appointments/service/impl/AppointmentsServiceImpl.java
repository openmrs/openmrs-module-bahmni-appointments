package org.openmrs.module.appointments.service.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Person;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointments.conflicts.AppointmentConflict;
import org.openmrs.module.appointments.dao.AppointmentAuditDao;
import org.openmrs.module.appointments.dao.AppointmentDao;
import org.openmrs.module.appointments.helper.AppointmentServiceHelper;
import org.openmrs.module.appointments.model.Appointment;
import org.openmrs.module.appointments.model.AppointmentAudit;
import org.openmrs.module.appointments.model.AppointmentProvider;
import org.openmrs.module.appointments.model.AppointmentProviderResponse;
import org.openmrs.module.appointments.model.AppointmentSearchRequest;
import org.openmrs.module.appointments.model.AppointmentServiceDefinition;
import org.openmrs.module.appointments.model.AppointmentServiceType;
import org.openmrs.module.appointments.model.AppointmentStatus;
import org.openmrs.module.appointments.notification.NotificationResult;
import org.openmrs.module.appointments.service.AppointmentsService;
import org.openmrs.module.appointments.validator.AppointmentStatusChangeValidator;
import org.openmrs.module.appointments.validator.AppointmentValidator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.openmrs.module.appointments.constants.PrivilegeConstants.MANAGE_APPOINTMENTS;
import static org.openmrs.module.appointments.constants.PrivilegeConstants.MANAGE_OWN_APPOINTMENTS;
import static org.openmrs.module.appointments.constants.PrivilegeConstants.RESET_APPOINTMENT_STATUS;
import static org.openmrs.module.appointments.util.DateUtil.getStartOfDay;


public class AppointmentsServiceImpl implements AppointmentsService, ApplicationEventPublisherAware {

    private static final String PRIVILEGES_EXCEPTION_CODE = "error.privilegesRequired";
    private Log log = LogFactory.getLog(this.getClass());
    private AppointmentDao appointmentDao;

    private List<AppointmentStatusChangeValidator> statusChangeValidators;

    private List<AppointmentValidator> appointmentValidators;

    private List<AppointmentValidator> editAppointmentValidators;

    private AppointmentAuditDao appointmentAuditDao;

    private AppointmentServiceHelper appointmentServiceHelper;

    private List<AppointmentConflict> appointmentConflicts;

    private ApplicationEventPublisher applicationEventPublisher;

    private TeleconsultationAppointmentService teleconsultationAppointmentService;

    private PatientAppointmentNotifierService appointmentNotifierService;

    public void setAppointmentDao(AppointmentDao appointmentDao) {
        this.appointmentDao = appointmentDao;
    }

    public void setStatusChangeValidators(List<AppointmentStatusChangeValidator> statusChangeValidators) {
        this.statusChangeValidators = statusChangeValidators;
    }

    public void setAppointmentValidators(List<AppointmentValidator> appointmentValidators) {
        this.appointmentValidators = appointmentValidators;
    }

    public void setAppointmentAuditDao(AppointmentAuditDao appointmentAuditDao) {
        this.appointmentAuditDao = appointmentAuditDao;
    }

    public void setAppointmentServiceHelper(AppointmentServiceHelper appointmentServiceHelper) {
        this.appointmentServiceHelper = appointmentServiceHelper;
    }

    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void setEditAppointmentValidators(List<AppointmentValidator> editAppointmentValidators) {
        this.editAppointmentValidators = editAppointmentValidators;
    }

    public void setAppointmentConflicts(List<AppointmentConflict> appointmentConflicts) {
        this.appointmentConflicts = appointmentConflicts;
    }

    public void setTeleconsultationAppointmentService(TeleconsultationAppointmentService teleconsultationAppointmentService) {
        this.teleconsultationAppointmentService = teleconsultationAppointmentService;
    }

    public void setAppointmentNotifierService(PatientAppointmentNotifierService appointmentNotifierService) {
        this.appointmentNotifierService = appointmentNotifierService;
    }

    private boolean validateIfUserHasSelfOrAllAppointmentsAccess(Appointment appointment) {
        return Context.hasPrivilege(MANAGE_APPOINTMENTS) ||
                isAppointmentNotAssignedToAnyProvider(appointment) ||
                isCurrentUserSamePersonAsOneOfTheAppointmentProviders(appointment.getProviders());
    }

    private boolean isAppointmentNotAssignedToAnyProvider(Appointment appointment) {
        return appointment.getProvidersWithResponse(AppointmentProviderResponse.ACCEPTED).isEmpty()
                && appointment.getProvidersWithResponse(AppointmentProviderResponse.AWAITING).isEmpty();
    }

    private boolean isCurrentUserSamePersonAsOneOfTheAppointmentProviders(Set<AppointmentProvider> providers) {
        return providers.stream()
                .anyMatch(provider -> provider.getProvider().getPerson().
                        equals(Context.getAuthenticatedUser().getPerson()));
    }

    @Transactional
    @Override
    public Appointment validateAndSave(Appointment appointment) throws APIException {
        validate(appointment, appointmentValidators);
        appointmentServiceHelper.checkAndAssignAppointmentNumber(appointment);
        Appointment prev = getAppointmentByUuid(appointment.getUuid());
        setupTeleconsultation(appointment);
        save(appointment);
        notifyUpdates(prev, appointment);
        return appointment;
    }

    @Transactional
    @Override
    public Appointment validateAndSave(String appointmentUuid, Supplier<Appointment> mapper) {
        Appointment prev = null;
        if (appointmentUuid != null && !"".equals(appointmentUuid)) {
            prev = getAppointmentByUuid(appointmentUuid);
        }
        Appointment appointment = mapper.get();
        validate(appointment, appointmentValidators);
        appointmentServiceHelper.checkAndAssignAppointmentNumber(appointment);
        setupTeleconsultation(appointment);
        save(appointment);
        notifyUpdates(prev, appointment);
        return appointment;
    }

    private void setupTeleconsultation(Appointment appointment) {
        log.error("1 uuid: " +  appointment.getUuid());
        log.error("1 Id: " +  appointment.getAppointmentId());
        log.error("1 date changed: " +  appointment.getDateChanged());
        log.error("1 date created: " +  appointment.getDateCreated());
        log.error("1 start date : " + appointment.getStartDateTime());
        log.error("1 link: " +  appointment.getTeleHealthVideoLink());
        if (appointment.getTeleconsultation() != null && appointment.getTeleconsultation()) {
            appointment.setTeleHealthVideoLink(teleconsultationAppointmentService.generateTeleconsultationLink(appointment));
        }
    }

    private void notifyUpdates(Appointment prev, Appointment appointment) {
        debugPrePostAppt(prev, appointment);
        if (appointment.getTeleconsultation() != null && appointment.getTeleconsultation()) {
            List<NotificationResult> notificationResults = appointmentNotifierService.notifyAll(appointment);
            Optional<NotificationResult> result = notificationResults.stream().filter(r -> r.getStatus() == NotificationResult.SUCCESS_STATUS).findFirst();
            result.ifPresent(r -> appointment.setEmailSent(true));
            notificationResults.stream().filter(r -> r.getStatus() != NotificationResult.SUCCESS_STATUS)
                    .forEach(nr -> log.error(String.format(
                            "Could not send notification for medium: %s, uuid: %s, status: %d, errMsg: %s",
                            nr.getMedium(), nr.getUuid(), nr.getStatus(), nr.getMessage())));
        }
    }

    private void debugPrePostAppt(Appointment prev, Appointment appointment) {
        log.error("before save: " + prev);
        log.error("equal?: " + (appointment == prev));
        log.error("post save: " + appointment);

        if (prev != null) {
            log.error("prev uuid: " + prev.getUuid());
            log.error("prev Id: " + prev.getAppointmentId());
            log.error("prev date changed: " + prev.getDateChanged());
            log.error("prev date created: " + prev.getDateCreated());
            log.error("prev start date : " + prev.getStartDateTime());
            log.error("prev link: " + prev.getTeleHealthVideoLink());
        } else {
            log.error("no previous instance" );
        }
        log.error("current uuid: " +  appointment.getUuid());
        log.error("current Id: " +  appointment.getAppointmentId());
        log.error("current date changed: " +  appointment.getDateChanged());
        log.error("current date created: " +  appointment.getDateCreated());
        log.error("current start date : " + appointment.getStartDateTime());
        log.error("current link: " +  appointment.getTeleHealthVideoLink());
    }


    private void save(Appointment appointment) {
        createAndSetAppointmentAudit(appointment);
        appointmentDao.save(appointment);
    }

    @Transactional
    @Override
    public void validate(Appointment appointment, List<AppointmentValidator> appointmentValidators) {
        if (!validateIfUserHasSelfOrAllAppointmentsAccess(appointment)) {
            throw new APIAuthenticationException(Context.getMessageSourceService().getMessage(PRIVILEGES_EXCEPTION_CODE,
                    new Object[]{MANAGE_APPOINTMENTS, MANAGE_OWN_APPOINTMENTS}, null));
        }
        appointmentServiceHelper.validate(appointment, appointmentValidators);
    }

    @Transactional
    @Override
    public List<Appointment> getAllAppointments(Date forDate) {
        List<Appointment> appointments = appointmentDao.getAllAppointments(forDate);
        return appointments.stream().filter(appointment -> !isServiceOrServiceTypeVoided(appointment)).collect(Collectors.toList());
    }

    private boolean isServiceOrServiceTypeVoided(Appointment appointment) {
        return (appointment.getService() != null && appointment.getService().getVoided()) ||
                (appointment.getServiceType() != null && appointment.getServiceType().getVoided());
    }

    /**
     * TODO: refactor. How can a search by an appointment return a list of appointments?
     *
     * @param appointment
     * @return
     */
    @Transactional
    @Override
    public List<Appointment> search(Appointment appointment) {
        List<Appointment> appointments = appointmentDao.search(appointment);
        return appointments.stream().filter(searchedAppointment -> !isServiceOrServiceTypeVoided(searchedAppointment)).collect(Collectors.toList());
    }

    @Transactional
    @Override
    public List<Appointment> getAllFutureAppointmentsForService(AppointmentServiceDefinition appointmentServiceDefinition) {
        return appointmentDao.getAllFutureAppointmentsForService(appointmentServiceDefinition);
    }

    @Transactional
    @Override
    public List<Appointment> getAllFutureAppointmentsForServiceType(AppointmentServiceType appointmentServiceType) {
        return appointmentDao.getAllFutureAppointmentsForServiceType(appointmentServiceType);
    }

    @Transactional
    @Override
    public List<Appointment> getAppointmentsForService(AppointmentServiceDefinition appointmentServiceDefinition, Date startDate, Date endDate, List<AppointmentStatus> appointmentStatusList) {
        return appointmentDao.getAppointmentsForService(appointmentServiceDefinition, startDate, endDate, appointmentStatusList);
    }

    @Transactional
    @Override
    public Appointment getAppointmentByUuid(String uuid) {
        Appointment appointment = appointmentDao.getAppointmentByUuid(uuid);
        return appointment;
    }

    @Transactional
    @Override
    public void changeStatus(Appointment appointment, String status, Date onDate) throws APIException {
        AppointmentStatus appointmentStatus = AppointmentStatus.valueOf(status);
        validateUserPrivilege(appointment, appointmentStatus);
        appointmentServiceHelper.validateStatusChangeAndGetErrors(appointment, appointmentStatus, statusChangeValidators);
        validateUserPrivilege(appointment, appointmentStatus);
        appointment.setStatus(appointmentStatus);
        appointmentDao.save(appointment);
        String notes = onDate != null ? onDate.toInstant().toString() : null;
        createEventInAppointmentAudit(appointment, notes);
    }

    private void validateUserPrivilege(Appointment appointment, AppointmentStatus appointmentStatus) {
        if (!validateIfUserHasSelfOrAllAppointmentsAccess(appointment)) {
            throw new APIAuthenticationException(Context.getMessageSourceService().getMessage(PRIVILEGES_EXCEPTION_CODE,
                    new Object[]{MANAGE_APPOINTMENTS}, null));
        }
        if (!isUserAllowedToResetStatus(appointmentStatus, appointment.getStatus())) {
            throw new APIAuthenticationException(Context.getMessageSourceService().getMessage(PRIVILEGES_EXCEPTION_CODE,
                    new Object[]{RESET_APPOINTMENT_STATUS}, null));
        }
    }

    private boolean isUserAllowedToResetStatus(AppointmentStatus toStatus, AppointmentStatus currentStatus) {
        if (!toStatus.equals(AppointmentStatus.Scheduled)) return true;
        if (currentStatus.equals(AppointmentStatus.Requested)) return true;
        return Context.hasPrivilege(RESET_APPOINTMENT_STATUS);
    }

    @Transactional
    @Override
    public List<Appointment> getAllAppointmentsInDateRange(Date startDate, Date endDate) {
        List<Appointment> appointments = appointmentDao.getAllAppointmentsInDateRange(startDate, endDate);
        return appointments.stream().filter(appointment -> !isServiceOrServiceTypeVoided(appointment)).collect(Collectors.toList());
    }

    @Transactional
    @Override
    public void undoStatusChange(Appointment appointment) throws APIException {
        if (!validateIfUserHasSelfOrAllAppointmentsAccess(appointment)) {
            throw new APIAuthenticationException(Context.getMessageSourceService().getMessage(PRIVILEGES_EXCEPTION_CODE,
                    new Object[]{MANAGE_APPOINTMENTS}, null));
        }
        AppointmentAudit statusChangeEvent = appointmentAuditDao.getPriorStatusChangeEvent(appointment);
        if (statusChangeEvent != null) {
            appointment.setStatus(statusChangeEvent.getStatus());
            appointmentDao.save(appointment);
            createEventInAppointmentAudit(appointment, statusChangeEvent.getNotes());
        } else
            throw new APIException("No status change actions to undo");
    }

    @Transactional
    @Override
    public List<Appointment> search(AppointmentSearchRequest appointmentSearchRequest) {
        if (isNull(appointmentSearchRequest.getStartDate())) {
            return null;
        }
        return appointmentDao.search(appointmentSearchRequest);
    }

    @Override
    public Map<Enum, List<Appointment>> getAppointmentConflicts(Appointment appointment) {
        return getAllConflicts(Collections.singletonList(appointment));
    }

    @Override
    public Map<Enum, List<Appointment>> getAppointmentsConflicts(List<Appointment> appointments) {
        List<Appointment> filteredAppointments = getNonVoidedFutureAppointments(appointments);
        return CollectionUtils.isEmpty(filteredAppointments) ? new HashMap<>() : getAllConflicts(filteredAppointments);
    }

    private Map<Enum, List<Appointment>> getAllConflicts(List<Appointment> appointments) {
        Map<Enum, List<Appointment>> conflictsMap = new HashMap<>();
        for (AppointmentConflict appointmentConflict : appointmentConflicts) {
            List<Appointment> conflictAppointments = appointmentConflict.getConflicts(appointments);
            if (CollectionUtils.isNotEmpty(conflictAppointments))
                conflictsMap.put(appointmentConflict.getType(), conflictAppointments);
        }
        return conflictsMap;
    }

    private List<Appointment> getNonVoidedFutureAppointments(List<Appointment> appointments) {
        return appointments.stream().filter(appointment -> {
            appointmentServiceHelper.checkAndAssignAppointmentNumber(appointment);
            return !(appointment.getVoided() || appointment.getStartDateTime().before(getStartOfDay()));
        }).collect(Collectors.toList());
    }

    @Transactional
    @Override
    public void updateAppointmentProviderResponse(AppointmentProvider providerWithNewResponse) {
        Appointment appointment = providerWithNewResponse.getAppointment();
        Set<AppointmentProvider> existingProviders = appointment.getProviders();

        if (CollectionUtils.isEmpty(existingProviders)) {
            throw new APIException("No providers present in Appointment");
        }
        AppointmentProvider existingProviderInAppointment = findProviderInAppointment(providerWithNewResponse, existingProviders);
        validateProviderResponseForSelf(existingProviderInAppointment);
        existingProviderInAppointment.setResponse(providerWithNewResponse.getResponse());

        if (isFirstAcceptForRequestedAppointment(providerWithNewResponse, appointment)) {
            changeStatus(appointment, AppointmentStatus.Scheduled.name(), Date.from(Instant.now()));
        } else {
            appointmentDao.save(appointment);
        }
        createAppointmentAudit(providerWithNewResponse, appointment, existingProviderInAppointment);
    }

    private AppointmentProvider findProviderInAppointment(AppointmentProvider providerWithNewResponse, Set<AppointmentProvider> providers) {
        Optional<AppointmentProvider> providerInAppointment = providers.stream().filter(
                provider -> provider.getProvider().equals(providerWithNewResponse.getProvider())
        ).findFirst();
        if (!providerInAppointment.isPresent()) throw new APIException("Provider is not part of Appointment");
        return providerInAppointment.get();
    }

    private void validateProviderResponseForSelf(AppointmentProvider appointmentProvider) {
        Person loggedInPerson = Context.getAuthenticatedUser().getPerson();
        Person providerPerson = appointmentProvider.getProvider().getPerson();
        if (!loggedInPerson.equals(providerPerson)) {
            throw new APIAuthenticationException("Cannot change Provider Response for other providers");
        }
    }

    @Transactional
    @Override
    public Appointment reschedule(String originalAppointmentUuid, Appointment newAppointment, boolean retainAppointmentNumber) {
        Appointment prevAppointment = getAppointmentByUuid(originalAppointmentUuid);
        if (prevAppointment == null) {
            //TODO: should we match the new appointment uuid as well?
            String msg = String.format("Can not identify appointment for rescheduling with %s", originalAppointmentUuid);
            log.error(msg);
            throw new RuntimeException(msg);
        }


        try {
            //cancel the previous appointment
            changeStatus(prevAppointment, AppointmentStatus.Cancelled.toString(), new Date());
            createEventInAppointmentAudit(prevAppointment,
                    appointmentServiceHelper.getAppointmentAsJsonString(prevAppointment));

            //create a new appointment
            newAppointment.setUuid(null);
            newAppointment.setDateCreated(null);
            newAppointment.setCreator(null);
            newAppointment.setDateChanged(null);
            newAppointment.setChangedBy(null);

            //TODO: should we copy the original appointment
            //newAppointment.setAppointmentNumber(prevAppointment.getAppointmentNumber());
            appointmentServiceHelper.checkAndAssignAppointmentNumber(newAppointment);

            newAppointment.setStatus(AppointmentStatus.Scheduled);
            validateAndSave(newAppointment);

            return newAppointment;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createEventInAppointmentAudit(Appointment appointment,
                                               String notes) {
        AppointmentAudit appointmentAuditEvent = appointmentServiceHelper.getAppointmentAuditEvent(appointment, notes);
        appointmentAuditDao.save(appointmentAuditEvent);
    }

    private boolean isFirstAcceptForRequestedAppointment(AppointmentProvider providerWithNewResponse, Appointment appointment) {
        return appointment.getStatus().equals(AppointmentStatus.Requested) &&
                providerWithNewResponse.getResponse().equals(AppointmentProviderResponse.ACCEPTED);
    }

    private void createAppointmentAudit(AppointmentProvider providerWithNewResponse, Appointment appointment, AppointmentProvider appointmentProvider) {
        String notes = String.format(
                "Changed Provider Response to %s for provider with UUID %s in appointment with UUID %s",
                providerWithNewResponse.getResponse(), appointmentProvider.getProvider().getUuid(), appointment.getUuid());
        createEventInAppointmentAudit(appointment, notes);
    }

    private void createAndSetAppointmentAudit(Appointment appointment) {
        AppointmentAudit appointmentAudit;
        try {
            String notes = appointmentServiceHelper.getAppointmentAsJsonString(appointment);
            appointmentAudit = appointmentServiceHelper.getAppointmentAuditEvent(appointment, notes);
        } catch (IOException e) {
            throw new APIException(e);
        }
        Set<AppointmentAudit> appointmentAudits = appointment.getAppointmentAudits();
        appointmentAudits.addAll(new HashSet<>(Collections.singleton(appointmentAudit)));
    }

}
