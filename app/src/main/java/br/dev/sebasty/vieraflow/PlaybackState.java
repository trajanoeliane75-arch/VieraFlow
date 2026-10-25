package br.dev.sebasty.vieraflow;

/** Receiver observations, deliberately separate from commands sent by the phone. */
final class PlaybackState {
    private String state = "UNKNOWN";
    private int failures;

    void reset() { state = "UNKNOWN"; failures = 0; }

    void update(DlnaController.Playback playback) {
        if (playback == null) {
            failures++;
            state = "UNKNOWN";
            return;
        }
        failures = 0;
        state = "ERROR_OCCURRED".equals(playback.status) ? "ERROR" : playback.state;
    }

    boolean active() {
        return "PLAYING".equals(state) || "PAUSED_PLAYBACK".equals(state)
                || "TRANSITIONING".equals(state);
    }

    boolean terminal() {
        return "STOPPED".equals(state) || "NO_MEDIA_PRESENT".equals(state) || "ERROR".equals(state);
    }

    String label() {
        switch (state) {
            case "PLAYING": return "Na TV · reproduzindo";
            case "PAUSED_PLAYBACK": return "Na TV · pausado";
            case "TRANSITIONING": return "Na TV · carregando";
            case "STOPPED": return "Na TV · parado";
            case "NO_MEDIA_PRESENT": return "Na TV · sem vídeo";
            case "ERROR": return "A TV informou erro na reprodução";
            default: return failures >= 3 ? "TV sem resposta · verifique a conexão"
                    : "Estado da TV indisponível · verificando";
        }
    }
}
