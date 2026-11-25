package org.andre.construx.modules.messaging.conversation;

import java.util.Map;

/** What the bot says, in both languages (Field updates contract §6). {@code {name}}-style parts are placeholders. */
public enum BotText {
    NOT_LINKED("Este número no está vinculado a Construx. Entra a tu perfil, toca \"Vincular WhatsApp\" y envía aquí el código.",
            "This number isn't linked to Construx. Open your profile, tap \"Link WhatsApp\" and send the code here."),
    CODE_INVALID("Ese código no es válido o ya venció. Genera uno nuevo en tu perfil.",
            "That code is invalid or expired. Generate a new one in your profile."),
    LINKED("¡Listo, {name}! Tu WhatsApp quedó vinculado. Escribe cualquier mensaje para actualizar tus tareas. Envía BAJA para desvincularlo.",
            "Done, {name}! Your WhatsApp is linked. Send any message to update your tasks. Send STOP to unlink."),
    UNLINKED("Tu número se desvinculó de Construx.",
            "Your number was unlinked from Construx."),
    PICK_SITE("¿En qué obra?", "Which site?"),
    PICK_TASK("{site}: ¿qué tarea?", "{site}: which task?"),
    SEE_MORE("Ver más", "See more"),
    NO_SITES("No tienes obras asignadas.", "You have no sites assigned."),
    NO_TASKS("No tienes tareas abiertas en {site}.", "You have no open tasks on {site}."),
    PICK_ACTION("{task}: ¿qué hiciste?", "{task}: what did you do?"),
    // The summary is SUMMARY, any detail lines (note, photos), a blank line and SAVE_IT.
    SUMMARY("📋 Avance de campo · {site}\n{task} → {action}", "📋 Field update · {site}\n{task} → {action}"),
    SAVE_IT("¿Lo guardo?", "Save it?"),
    SAVED("✅ Guardado en Construx.", "✅ Saved to Construx."),
    CANCELLED("Cancelado. Escribe de nuevo cuando quieras.", "Cancelled. Send a message to start again."),
    CHANGED("Esta tarea cambió mientras tanto. Empieza de nuevo.", "This task changed in the meantime. Start again."),
    USE_BUTTONS("Por ahora usa los botones.", "Please use the buttons for now."),

    // Labels: buttons (at most 20 characters), the button that opens a list, Task statuses, summary actions.
    CHOOSE("Elegir", "Choose"),
    START("Iniciar", "Start"),
    FINISH("Terminar", "Finish"),
    YES("Sí", "Yes"),
    NO("No", "No"),
    ANOTHER_TASK("Otra tarea", "Another task"),
    STATUS_TODO("Por hacer", "To do"),
    STATUS_IN_PROGRESS("En curso", "In progress"),
    STARTED("Iniciada", "Started"),
    FINISHED("Terminada", "Finished");

    private final String es;
    private final String en;

    BotText(String es, String en) {
        this.es = es;
        this.en = en;
    }

    public String in(Language language) {
        return language == Language.ES ? es : en;
    }

    /** The text with each {@code {key}} replaced by its value. */
    public String in(Language language, Map<String, String> values) {
        String text = in(language);
        for (Map.Entry<String, String> value : values.entrySet()) {
            text = text.replace("{" + value.getKey() + "}", value.getValue());
        }
        return text;
    }

    /** Before a number is linked its language is unknown: Spanish first, then English, in one message. */
    public String bilingual() {
        return es + "\n\n" + en;
    }
}
