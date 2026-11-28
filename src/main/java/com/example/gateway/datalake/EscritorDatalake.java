package com.example.gateway.datalake;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Escribe cada línea del datalake en la salida estándar con el prefijo {@value #PREFIJO}
 * y, si existe LOG_DIR, también (sin prefijo) en
 * {@code LOG_DIR/gateway-<instancia>-AAAAMMDD-HH.jsonl} (hora UTC, append).
 * <p>
 * La escritura ocurre en un único hilo aparte con una cola acotada: la petición nunca
 * espera por la consola ni por el disco. Si la cola se llena (disco lento o bloqueado),
 * la línea se descarta y se cuenta, en lugar de frenar al Gateway. Un solo hilo
 * también garantiza que dos líneas nunca se mezclen en el archivo.
 */
@Component
public class EscritorDatalake implements SalidaDatalake, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EscritorDatalake.class);

    static final String PREFIJO = "DLK ";
    private static final int CAPACIDAD_COLA = 10_000;
    private static final DateTimeFormatter FORMATO_HORA =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH").withZone(ZoneOffset.UTC);

    private final Path carpeta;
    private final String instancia;
    private final Executor ejecutor;
    private final PrintStream consola;
    private final AtomicLong descartadas = new AtomicLong();

    @Autowired
    public EscritorDatalake(@Value("${LOG_DIR:}") String logDir, @Value("${HOSTNAME:}") String hostname) {
        this(logDir, hostname, crearHilo(), System.out);
    }

    // Para las pruebas: ejecutor directo y consola capturada
    EscritorDatalake(String logDir, String hostname, Executor ejecutor, PrintStream consola) {
        this.carpeta = logDir == null || logDir.isBlank() ? null : Path.of(logDir.trim());
        this.instancia = RegistroPeticionesFilter.instanciaDe(hostname);
        this.ejecutor = ejecutor;
        this.consola = consola;
    }

    @Override
    public void escribir(Instant ts, String json) {
        try {
            ejecutor.execute(() -> escribirAhora(ts, json));
        } catch (RejectedExecutionException ex) {
            // Cola llena o apagando: se pierde la línea, nunca la petición
            if (descartadas.incrementAndGet() % 1000 == 1) {
                log.warn("Cola del datalake llena: {} líneas descartadas hasta ahora", descartadas.get());
            }
        }
    }

    private void escribirAhora(Instant ts, String json) {
        try {
            consola.println(PREFIJO + json);
            consola.flush();
        } catch (RuntimeException ex) {
            log.warn("No se pudo escribir la línea del datalake en la consola: {}", ex.toString());
        }
        if (carpeta == null) {
            return;
        }
        try {
            Files.createDirectories(carpeta);
            Files.writeString(archivoDe(ts), json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            log.warn("No se pudo escribir la línea del datalake en {}: {}", carpeta, ex.toString());
        }
    }

    Path archivoDe(Instant ts) {
        // La instancia viene de HOSTNAME: se limpia para que siempre sea un nombre de archivo válido
        String instanciaArchivo = instancia.replaceAll("[^A-Za-z0-9._-]", "_");
        return carpeta.resolve(RegistroPeticionesFilter.SERVICIO + "-" + instanciaArchivo + "-"
                + FORMATO_HORA.format(ts) + ".jsonl");
    }

    long descartadas() {
        return descartadas.get();
    }

    private static ExecutorService crearHilo() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACIDAD_COLA),
                tarea -> {
                    Thread hilo = new Thread(tarea, "datalake-escritor");
                    hilo.setDaemon(true);
                    return hilo;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Al apagar, se da un momento para escribir las líneas pendientes. */
    @Override
    public void destroy() throws InterruptedException {
        if (ejecutor instanceof ExecutorService servicio) {
            servicio.shutdown();
            servicio.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
