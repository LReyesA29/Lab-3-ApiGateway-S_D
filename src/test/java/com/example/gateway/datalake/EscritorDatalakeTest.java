package com.example.gateway.datalake;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EscritorDatalakeTest {

    private static final Executor DIRECTO = Runnable::run;
    private static final Instant TS = Instant.parse("2026-11-20T14:03:22.123Z");

    private final ByteArrayOutputStream consola = new ByteArrayOutputStream();
    private final PrintStream salida = new PrintStream(consola, true, StandardCharsets.UTF_8);

    @Test
    void sinLogDirSoloImprimeConPrefijo() {
        new EscritorDatalake("", "pod-1", DIRECTO, salida).escribir(TS, "{\"a\":1}");
        assertEquals("DLK {\"a\":1}" + System.lineSeparator(), consola.toString(StandardCharsets.UTF_8));
    }

    @Test
    void conLogDirAgregaLaLineaSinPrefijoAlArchivoDeLaHoraUtc(@TempDir Path tmp) throws Exception {
        Path carpeta = tmp.resolve("logs/datalake");   // no existe: se crea
        EscritorDatalake escritor = new EscritorDatalake(carpeta.toString(), "pod-1", DIRECTO, salida);

        escritor.escribir(TS, "{\"a\":1}");
        escritor.escribir(TS, "{\"a\":2}");
        escritor.escribir(Instant.parse("2026-11-20T15:00:00.000Z"), "{\"a\":3}");

        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"),
                Files.readAllLines(carpeta.resolve("gateway-pod-1-20261120-14.jsonl")));
        assertEquals(List.of("{\"a\":3}"), Files.readAllLines(carpeta.resolve("gateway-pod-1-20261120-15.jsonl")));
        assertTrue(consola.toString(StandardCharsets.UTF_8).startsWith("DLK {\"a\":1}"));
    }

    @Test
    void sinHostnameLaInstanciaEsLocal(@TempDir Path tmp) {
        EscritorDatalake escritor = new EscritorDatalake(tmp.toString(), null, DIRECTO, salida);
        assertEquals(tmp.resolve("gateway-local-20261120-14.jsonl"), escritor.archivoDe(TS));
    }

    @Test
    void unaInstanciaConCaracteresRarosDaUnNombreDeArchivoValido(@TempDir Path tmp) {
        EscritorDatalake escritor = new EscritorDatalake(tmp.toString(), "ip-10-0-1-5/../x", DIRECTO, salida);
        assertEquals(tmp.resolve("gateway-ip-10-0-1-5_.._x-20261120-14.jsonl"), escritor.archivoDe(TS));
    }

    @Test
    void siNoSePuedeEscribirElArchivoNoLanzaYSigueImprimiendo(@TempDir Path tmp) throws Exception {
        // LOG_DIR apunta a un archivo, no a una carpeta: createDirectories falla
        Path archivo = Files.writeString(tmp.resolve("no-es-carpeta"), "x");
        EscritorDatalake escritor = new EscritorDatalake(archivo.toString(), "pod-1", DIRECTO, salida);

        assertDoesNotThrow(() -> escritor.escribir(TS, "{\"a\":1}"));
        assertTrue(consola.toString(StandardCharsets.UTF_8).contains("DLK {\"a\":1}"));
    }

    @Test
    void siLaColaRechazaLaLineaSeDescartaSinLanzar() {
        Executor lleno = tarea -> {
            throw new java.util.concurrent.RejectedExecutionException("cola llena");
        };
        EscritorDatalake escritor = new EscritorDatalake("", "pod-1", lleno, salida);

        assertDoesNotThrow(() -> escritor.escribir(TS, "{\"a\":1}"));
        assertEquals(1, escritor.descartadas());
    }

    @Test
    void elHiloRealEscribeLaLineaSinBloquear() throws Exception {
        EscritorDatalake escritor = new EscritorDatalake("", "pod-1");
        PrintStream original = System.out;
        // El constructor de Spring ya tomó System.out; se verifica solo que no bloquee ni lance
        assertDoesNotThrow(() -> escritor.escribir(TS, "{\"prueba\":\"hilo\"}"));
        escritor.destroy();
        assertEquals(original, System.out);
    }
}
