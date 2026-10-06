package com.frauddetector.domain;

/**
 * A patient referenced by claims (PRD sections 23-24).
 */
public final class Patient {

    private final String patientId;
    private final String name;
    private final int age;
    private final String gender;
    private final String location;
    private final String insuranceId;

    public Patient(String patientId, String name, int age, String gender,
                   String location, String insuranceId) {
        this.patientId = patientId;
        this.name = name;
        this.age = age;
        this.gender = gender;
        this.location = location;
        this.insuranceId = insuranceId;
    }

    public String getPatientId() {
        return patientId;
    }

    public String getName() {
        return name;
    }

    public int getAge() {
        return age;
    }

    public String getGender() {
        return gender;
    }

    public String getLocation() {
        return location;
    }

    public String getInsuranceId() {
        return insuranceId;
    }
}
