# ATMP FHIR Server Data Requirements

This document describes how participating sites should represent ATMP transfer data in their local FHIR server so that a DSF process plugin can identify eligible patients, collect their laboratory Observations, and send new Observations to the ATMP platform.

The ATMP platform receives laboratory `Observation` resources. The DSF process does not decide study membership from the Observations themselves. Study membership is represented by `ResearchStudy` and `ResearchSubject`.

## Required Resources

Each participating site must ensure that the following resources exist in the FHIR server accessible to the DSF process:

| Resource | Purpose |
| --- | --- |
| `ResearchStudy` | Represents the ATMP study |
| `ResearchSubject` | Represents one patient participating in the ATMP study. |
| `Patient` | The patient referenced by the `ResearchSubject` and by the medical Observations. |
| `Observation` | Laboratory values that may be sent to the ATMP platform. |

## Relationship Model

The resources must be connected like this:

```text
ResearchStudy/atmp-study
  <- ResearchSubject.study

ResearchSubject/<subject-id>
  -> ResearchSubject.individual = Patient/<patient-id>
  -> ResearchSubject.identifier.value = <ATMP pseudonym>

Observation/<observation-id>
  -> Observation.subject = Patient/<patient-id>
```

The ATMP pseudonym must be stored on the `ResearchSubject.identifier`. 

## ResearchStudy

There should be one `ResearchStudy` resource identifying the ATMP study.

profile:

```text
https://www.medizininformatik-initiative.de/fhir/modul-studie/StructureDefinition/mii-pr-studie-studie
```

Minimum example:

```json
{
  "resourceType": "ResearchStudy",
  "id": "atmp-study",
  "meta": {
    "profile": [
      "https://www.medizininformatik-initiative.de/fhir/modul-studie/StructureDefinition/mii-pr-studie-studie"
    ]
  },
  "identifier": [
    {
      "system": "https://ukhd.de/fhir/sid/research-study",
      "value": "ATMP"
    }
  ],
  "title": "ATMP Study",
  "status": "active"
}
```

Sites may adapt the `id`, `identifier.system`, and `title`, but the identifier should be stable and known to the DSF process.

## ResearchSubject

Each participating patient must have one `ResearchSubject` linked to the ATMP `ResearchStudy`.

profile:

```text
https://www.medizininformatik-initiative.de/fhir/modul-studie/StructureDefinition/mii-pr-studie-proband
```

Required content:

| Element | Requirement |
| --- | --- |
| `ResearchSubject.study.reference` | Must reference the ATMP `ResearchStudy`. |
| `ResearchSubject.individual.reference` | Must reference the local `Patient`. |
| `ResearchSubject.identifier.value` | Must contain the ATMP pseudonym used when sending data to the ATMP platform. |
| `ResearchSubject.identifier.type` | Should use `ANON` from `http://terminology.hl7.org/CodeSystem/v2-0203`. |
| `ResearchSubject.status` | Should indicate active study participation, for example `on-study`. |

Minimum example:

```json
{
  "resourceType": "ResearchSubject",
  "id": "atmp-subject-001",
  "identifier": [
    {
      "type": {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/v2-0203",
            "code": "ANON"
          }
        ]
      },
      "system": "https://ukhd.de/fhir/sid/atmp-pseudonym",
      "value": "<ATMP_PSEUDONYM>"
    }
  ],
  "status": "on-study",
  "study": {
    "reference": "ResearchStudy/atmp-study"
  },
  "individual": {
    "reference": "Patient/<PATIENT_ID>"
  }
}
```

`<ATMP_PSEUDONYM>` is the pseudonym that will be used when the DSF process sends data to the ATMP platform.

`Patient/<PATIENT_ID>` must reference an existing `Patient` resource on the same FHIR server.

## Patient

The `Patient` resource must exist before the DSF process runs.

The DSF process uses the `ResearchSubject.individual.reference` to identify the patient whose Observations should be queried.

Minimum example:

```json
{
  "resourceType": "Patient",
  "id": "<PATIENT_ID>",
  "identifier": [
    {
      "system": "https://example.org/fhir/sid/local-patient-id",
      "value": "<LOCAL_PATIENT_IDENTIFIER>"
    }
  ]
}
```

## Observation

Laboratory Observations must exist on the FHIR server and must reference the same `Patient` resource that is linked from the `ResearchSubject`.

profile:

```text
https://www.medizininformatik-initiative.de/fhir/core/modul-labor/StructureDefinition/ObservationLab
```

Required content:

| Element | Requirement |
| --- | --- |
| `Observation.id` | Stable technical FHIR id. |
| `Observation.identifier` | Stable business identifier. |
| `Observation.status` | Usually `final`. |
| `Observation.category` | Laboratory category. |
| `Observation.code` | LOINC code. |
| `Observation.subject.reference` | Reference to the same local `Patient` resource. |
| `Observation.effectiveDateTime` | Time of the observation. |
| `Observation.valueQuantity` | Laboratory value and unit |

Minimum example:

```json
{
  "resourceType": "Observation",
  "id": "SWL-2507011234-2025-07-01-1",
  "meta": {
    "profile": [
      "https://www.medizininformatik-initiative.de/fhir/core/modul-labor/StructureDefinition/ObservationLab"
    ]
  },
  "identifier": [
    {
      "type": {
        "coding": [
          {
            "system": "http://terminology.hl7.org/CodeSystem/v2-0203",
            "code": "OBI"
          }
        ]
      },
      "system": "1.2.276.0.76.3.1.78.1.1.10.70.3.1",
      "value": "SWL-2507011234-2025-07-01-1"
    }
  ],
  "status": "final",
  "category": [
    {
      "coding": [
        {
          "system": "http://loinc.org",
          "code": "26436-6"
        },
        {
          "system": "http://terminology.hl7.org/CodeSystem/observation-category",
          "code": "laboratory"
        }
      ]
    }
  ],
  "code": {
    "coding": [
      {
        "system": "http://loinc.org",
        "code": "6690-2"
      }
    ]
  },
  "subject": {
    "reference": "Patient/<PATIENT_ID>"
  },
  "effectiveDateTime": "2025-07-01T12:40:00+01:00",
  "valueQuantity": {
    "value": 4.23,
    "unit": "/nl",
    "system": "http://unitsofmeasure.org",
    "code": "/nl"
  }
}
```

## Transaction Bundle Template

The following transaction bundle can be used as a template to create the ATMP `ResearchStudy` and one `ResearchSubject`.

Participating sites must replace:

| Placeholder | Meaning |
| --- | --- |
| `<PATIENT_ID>` | Logical id of the existing local `Patient` resource. |
| `<ATMP_PSEUDONYM>` | Pseudonym that will be sent to the ATMP platform. |
| `<SUBJECT_ID>` | Stable local FHIR id for the `ResearchSubject`. |
| `https://example.org/fhir/sid/...` | Site-specific identifier systems. |

```json
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "fullUrl": "ResearchStudy/atmp-study",
      "resource": {
        "resourceType": "ResearchStudy",
        "id": "atmp-study",
        "meta": {
          "profile": [
            "https://www.medizininformatik-initiative.de/fhir/modul-studie/StructureDefinition/mii-pr-studie-studie"
          ]
        },
        "identifier": [
          {
            "system": "https://ukhd.de/fhir/sid/research-study",
            "value": "ATMP"
          }
        ],
        "title": "ATMP Study",
        "status": "active"
      },
      "request": {
        "method": "PUT",
        "url": "ResearchStudy/atmp-study"
      }
    },
    {
      "fullUrl": "ResearchSubject/<SUBJECT_ID>",
      "resource": {
        "resourceType": "ResearchSubject",
        "id": "<SUBJECT_ID>",
        "identifier": [
          {
            "type": {
              "coding": [
                {
                  "system": "http://terminology.hl7.org/CodeSystem/v2-0203",
                  "code": "ANON"
                }
              ]
            },
            "system": "https://ukhd.de/fhir/sid/atmp-pseudonym",
            "value": "<ATMP_PSEUDONYM>"
          }
        ],
        "status": "on-study",
        "study": {
          "reference": "ResearchStudy/atmp-study"
        },
        "individual": {
          "reference": "Patient/<PATIENT_ID>"
        }
      },
      "request": {
        "method": "PUT",
        "url": "ResearchSubject/<SUBJECT_ID>"
      }
    }
  ]
}
```
