package com.frauddetector.repository;

import com.frauddetector.domain.Patient;

/** In-memory {@link Patient} store keyed by patientId. */
public final class PatientRepository extends InMemoryRepository<String, Patient> {

    public PatientRepository() {
        super(Patient::getPatientId);
    }
}
