package com.openmrs.notification.model;

/**
 * Bezorgkanaal van een provider. Het systeem is bewust <b>SMS/telefoon-only</b>
 * (e-mail is verwijderd — telefoonnummer volstaat). Een nieuw kanaal toevoegen
 * is een kwestie van hier een waarde bijzetten en een provider die dat kanaal
 * implementeert.
 */
public enum NotificationChannel {
    SMS
}
