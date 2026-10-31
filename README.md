# Distri_Lab3_ApiGateway

API Gateway del sistema académico distribuido desarrollado para el Laboratorio 3 de
Sistemas Distribuidos. Actúa como punto de entrada único hacia tres microservicios
independientes (Estudiantes, Materias e Inscripciones), encargándose del
enrutamiento, la autenticación y la composición de datos entre ellos.

## Arquitectura

```
                    ┌────────────────────┐
   Cliente  ──────► │     API Gateway     │
                    │      (puerto 3000)  │
                    └──────────┬─────────┘
                               │
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
     Estudiantes           Materias          Inscripciones
     (puerto 3001)       (puerto 3002)        (puerto 3003)
```

Cada microservicio es un proyecto independiente, con su propia base de datos y
ciclo de vida. El Gateway no accede a ninguna base de datos directamente: toda la
comunicación con los módulos ocurre por HTTP.

## Tecnologías

| Componente | Detalle |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 4.0.3 |
| Cliente HTTP | RestTemplate |
| Autenticación | JWT (io.jsonwebtoken) |
| Build | Maven |

## Requisitos previos

- JDK 21
- Maven 3.9+
- Los tres microservicios (Estudiantes, Materias, Inscripciones) corriendo y
  accesibles en las URLs configuradas

## Configuración

Las URLs de los microservicios se definen en
`src/main/resources/application.properties`:

```properties
server.port=3000

modulos.estudiantes.url=${ESTUDIANTES_URL}
modulos.materias.url=${MATERIAS_URL}
modulos.inscripciones.url=${INSCRIPCIONES_URL}
modulos.autenticacion.url=${AUTENTICACION_URL}

jwt.secreto=${JWT_SECRET}
cors.allowed-origins=${FRONTEND_URL}
```

Toda la configuración llega por variables de entorno, sin valores por defecto:
si falta alguna, el Gateway no arranca. `JWT_SECRET` debe ser el mismo con el
que firma el módulo de Autenticación (mínimo 32 caracteres).

| Variable | Descripción |
|---|---|
| `JWT_SECRET` | Secreto HS256 compartido con el módulo de Autenticación |
| `FRONTEND_URL` | Origen del frontend permitido por CORS, p. ej. `http://localhost:4200` (sin `/` final; varios separados por coma; no admite `*`) |
| `ESTUDIANTES_URL` | URL del módulo Estudiantes |
| `MATERIAS_URL` | URL del módulo Materias |
| `INSCRIPCIONES_URL` | URL del módulo Inscripciones |
| `AUTENTICACION_URL` | URL del módulo de Autenticación |

Para desarrollo local, el perfil `local` (`application-local.properties`)
apunta las URLs a `localhost:3001-3004` y `FRONTEND_URL` a `http://localhost:4200`;
`JWT_SECRET` sigue siendo obligatorio.
`.env.example` sirve de plantilla para `docker run --env-file`.

## Ejecución

```bash
# PowerShell: $env:JWT_SECRET="<mismo secreto que Autenticacion>"
export JWT_SECRET="<mismo secreto que Autenticacion>"
# Con el perfil local, FRONTEND_URL vale http://localhost:4200 si no se define
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**Con Docker** (build multi-etapa sobre `amazoncorretto:21-alpine`; la imagen
final solo contiene el `.jar` y corre sin root):

```bash
docker build -t api-gateway .
cp .env.example .env   # completar JWT_SECRET
docker run -p 3000:3000 --env-file .env api-gateway
```

El servicio queda disponible en `http://localhost:3000`.

## Autenticación

Los tokens los emite el módulo de **Autenticación** (puerto 3004); el Gateway
reenvía `/api/auth/**` a ese módulo y solo **valida** los JWT (firma,
expiración y claim `rol`) en `JwtAuthenticationFilter`.

**Obtener un token** (usuarios creados por el seed del módulo de Autenticación):
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"admin","clave":"<SEED_ADMIN_CLAVE>"}'
```

Respuesta:
```json
{ "token": "eyJhbGciOiJIUzI1NiJ9..." }
```

**Usarlo en las siguientes peticiones:**
```bash
curl http://localhost:3000/api/estudiantes/1 \
  -H "Authorization: Bearer <token>"
```

### Control de acceso por rol (RBAC)

Las reglas viven en `PoliticaAcceso`:

| Ruta / método | Público | ESTUDIANTE | ADMIN |
|---|---|---|---|
| `POST /api/auth/login` | ✅ | ✅ | ✅ |
| `GET /actuator/health` | ✅ | ✅ | ✅ |
| `GET /api/estudiantes/**`, `/api/materias/**`, `/api/inscripciones/**` | ❌ | ✅ | ✅ |
| `GET /api/estudiantes/{id}/detalle` | ❌ | ✅ | ✅ |
| `GET /api/cursos/{id}/detalle` | ❌ | ❌ | ✅ |
| `POST` / `PUT` / `PATCH` / `DELETE` sobre cualquier ruta | ❌ | ❌ | ✅ |

| Situación | Respuesta |
|---|---|
| Sin token, token mal formado, firma inválida o expirado | `401 Unauthorized` |
| Token válido, pero el rol no tiene permiso (o no trae `rol`) | `403 Forbidden` |

Las reglas se evalúan sobre la ruta ya normalizada por el servidor, de modo que
rutas como `/actuator/health/../api/...` no heredan el acceso público.

### CORS

`CorsConfig` habilita CORS para que el frontend consuma el Gateway directamente
desde otro origen. El único origen permitido es el de `FRONTEND_URL`.

| Parámetro | Valor |
|---|---|
| Orígenes | `FRONTEND_URL` (obligatoria) |
| Métodos | `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS` |
| Cabeceras | `Authorization`, `Content-Type`, `Accept` |
| Caché del preflight | 1 hora (`Access-Control-Max-Age: 3600`) |

La configuración se aplica con un `WebMvcConfigurer` y con un `CorsFilter` de máxima
prioridad, que corre **antes** de `JwtAuthenticationFilter`. Así:

- El preflight (`OPTIONS` con `Origin` y `Access-Control-Request-Method`) se responde
  sin exigir token; `JwtAuthenticationFilter` además lo deja pasar explícitamente.
- Los `401`/`403` del filtro JWT también llevan las cabeceras CORS, de modo que el
  frontend puede leer el código (p. ej. para redirigir al login) en lugar de ver un
  "error de CORS".
- Una petición o preflight desde un origen no permitido recibe `403`.

Las peticiones sin cabecera `Origin` (curl, otros servicios o el frontend servido por
nginx con proxy `/api`, que es del mismo origen) no se ven afectadas.

## Enrutamiento

El Gateway reenvía cada petición al microservicio correspondiente según el
prefijo de la ruta, preservando método HTTP, query params y body:

| Prefijo | Microservicio destino |
|---|---|
| `/api/estudiantes/**` | Estudiantes |
| `/api/materias/**` | Materias |
| `/api/inscripciones/**` | Inscripciones |
| `/api/auth/**` | Autenticación |

El cuerpo se reenvía como bytes sin transformar y con su `Content-Type`
original, de modo que las subidas `multipart/form-data` (fotografía del
estudiante, contenido temático) llegan intactas. El Gateway no procesa el
multipart (`spring.servlet.multipart.enabled=false`): los límites de tamaño y
formato los aplica cada microservicio.

Si el microservicio responde con un error propio (400, 404, 409, 413, etc.), ese mismo
código y cuerpo se devuelven sin modificar. Si el microservicio no responde
(caído, tiempo de espera agotado), el Gateway responde `502 Bad Gateway`:

```json
{ "error": "Servicio no disponible: http://localhost:3001" }
```

## Endpoints de composición

```
GET /api/estudiantes/{id}/detalle
```

Implementa el patrón Backend for Frontend (BFF): orquesta varias llamadas a los
tres microservicios y devuelve la información combinada en una sola respuesta.

**Secuencia de llamadas:**

1. `GET /api/estudiantes/{id}` — datos del estudiante
2. `GET /api/inscripciones?estudianteId={id}` — inscripciones del estudiante (con sus notas)
3. Por cada inscripción: `GET /api/materias/cursos/{cursoId}` — datos del curso
4. Por cada curso: `GET /api/materias/docentes/{docenteId}` — datos del docente
5. El Gateway ensambla el resultado final

**Respuesta:**

```json
{
  "estudiante": {
    "_id": "6501f8a2c9d4e5f6a7b8c9d0",
    "nombre": "Laura",
    "apellido": "Gómez",
    "correo": "laura.gomez@ejemplo.com",
    "fecha_nacimiento": "2003-05-20T00:00:00.000Z",
    "programa": {
      "_id": "60d5ec49f1b2c8b1f8e4e1a1",
      "nombre": "Ingeniería de Sistemas y Computación",
      "codigo": "ISC-01",
      "facultad": "Facultad de Ingeniería"
    },
    "contacto": {
      "telefono": "3001234567",
      "direccion": "Calle 10 #5-30",
      "ciudad": "Sogamoso"
    }
  },
  "inscripciones": [
    {
      "_id": "6501f8a2c9d4e5f6a7b8c9d1",
      "id": "6501f8a2c9d4e5f6a7b8c9d1",
      "estudianteId": "6501f8a2c9d4e5f6a7b8c9d0",
      "cursoId": "1",
      "periodo": "2026-2",
      "estado": "activa",
      "curso": {
        "id": 1,
        "materiaId": 1,
        "materiaNombre": "Sistemas Distribuidos",
        "docenteId": 1,
        "horario": "Lunes y Miércoles 14:00-16:00",
        "periodo": "2026-2",
        "cupo": 30,
        "aula": "Laboratorio 204",
        "modalidad": "PRESENCIAL",
        "estado": "ACTIVO",
        "docente": {
          "id": 1,
          "nombres": "Carlos Andrés",
          "apellidos": "Ramírez López",
          "correoInstitucional": "carlos.ramirez@uptc.edu.co",
          "especialidad": "Sistemas Distribuidos y Cloud Computing",
          "activo": true
        }
      },
      "notas": [
        { "tipo": "Parcial 1", "valor": 4.2 },
        { "tipo": "Quiz 1", "valor": 4.5 }
      ]
    }
  ]
}
```

> Nota: `estudiante.programa`, `inscripciones[].curso` y `curso.docente` vienen
> completos porque el Gateway hace `populate`/composición sobre cada uno — no
> son solo el ID de referencia. Los ids de `curso`/`docente` son numéricos
> (vienen de Materias, PostgreSQL); los de `estudiante`/`inscripcion` son
> ObjectId de MongoDB (string de 24 caracteres).

**Errores:**

| Situación | Respuesta |
|---|---|
| El estudiante no existe | El código y cuerpo que devuelva el módulo Estudiantes (normalmente 404) |
| Un microservicio no responde | `502 Bad Gateway` |
| La respuesta de un microservicio no tiene la forma esperada | `500 Internal Server Error` |

```
GET /api/cursos/{id}/detalle
```

Mismo patrón BFF que el endpoint anterior, en la dirección opuesta: dado un
curso, muestra quién lo dicta y quién está inscrito.

**Secuencia de llamadas:**

1. `GET /api/materias/cursos/{id}` — datos del curso
2. `GET /api/materias/docentes/{docenteId}` — datos del docente que lo dicta
3. `GET /api/inscripciones?cursoId={id}` — inscripciones del curso
4. Por cada inscripción: `GET /api/estudiantes/{estudianteId}` — datos del estudiante inscrito
5. El Gateway ensambla el resultado final

**Respuesta:**

```json
{
  "curso": {
    "id": 1,
    "materiaId": 1,
    "materiaNombre": "Sistemas Distribuidos",
    "docenteId": 1,
    "horario": "Lunes y Miércoles 14:00-16:00",
    "periodo": "2026-2",
    "cupo": 30,
    "aula": "Laboratorio 204",
    "modalidad": "PRESENCIAL",
    "estado": "ACTIVO",
    "docente": {
      "id": 1,
      "nombres": "Carlos Andrés",
      "apellidos": "Ramírez López",
      "correoInstitucional": "carlos.ramirez@uptc.edu.co",
      "especialidad": "Sistemas Distribuidos y Cloud Computing",
      "activo": true
    }
  },
  "estudiantes": [
    {
      "_id": "6501f8a2c9d4e5f6a7b8c9d0",
      "nombre": "Laura",
      "apellido": "Gómez",
      "correo": "laura.gomez@ejemplo.com",
      "fecha_nacimiento": "2003-05-20T00:00:00.000Z",
      "programa": {
        "_id": "60d5ec49f1b2c8b1f8e4e1a1",
        "nombre": "Ingeniería de Sistemas y Computación",
        "codigo": "ISC-01",
        "facultad": "Facultad de Ingeniería"
      },
      "contacto": {
        "telefono": "3001234567",
        "direccion": "Calle 10 #5-30",
        "ciudad": "Sogamoso"
      }
    }
  ]
}
```

> Si el curso no tiene inscripciones, la respuesta trae igual `curso` y
> `curso.docente`, con `estudiantes` como una lista vacía (no un error).

**Errores:**

| Situación | Respuesta |
|---|---|
| El curso no existe | El código y cuerpo que devuelva el módulo Materias (normalmente 404) |
| Un microservicio no responde | `502 Bad Gateway` |
| La respuesta de un microservicio no tiene la forma esperada | `500 Internal Server Error` |

## Decisiones de diseño

- **RestTemplate en lugar de Spring Cloud Gateway.** El enrutamiento y la
  composición se implementan a mano sobre `RestTemplate`, lo que mantiene el
  flujo de cada petición explícito y fácil de seguir.
- **JWT y RBAC sin Spring Security completo.** Un filtro (`OncePerRequestFilter`)
  valida el token y aplica las reglas de `PoliticaAcceso` en cada petición. Es
  suficiente para el alcance del laboratorio, sin la configuración adicional que
  implicaría una integración completa con Spring Security.
- **El Gateway no emite tokens.** La identidad y los roles los gestiona el módulo
  de Autenticación; el Gateway solo comparte el secreto para verificar la firma.
- **JSON genérico (`JsonNode`) en el endpoint de composición.** En lugar de DTOs
  tipados, el endpoint trabaja directamente con la estructura JSON que devuelve
  cada microservicio, lo que permite adaptarse a cambios en los contratos de los
  módulos sin recompilar clases adicionales.
