package com.example.gateway.datalake;

import java.time.Instant;

/** Destino de las líneas del datalake (consola y, opcionalmente, archivo). */
public interface SalidaDatalake {

    /**
     * @param ts   instante de la línea; decide el archivo por hora (UTC)
     * @param json la línea JSON compacta, sin prefijo ni salto de línea
     */
    void escribir(Instant ts, String json);
}
