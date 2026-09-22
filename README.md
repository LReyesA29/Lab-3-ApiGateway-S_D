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

Las URLs de los microservicios y los parámetros del JWT se definen en
`src/main/resources/application.properties`:

```properties
server.port=3000

modulos.estudiantes.url=http://localhost:3001
modulos.materias.url=http://localhost:3002
modulos.inscripciones.url=http://localhost:3003

jwt.secreto=<clave de al menos 32 caracteres>
jwt.expiracion-minutos=60
```

## Ejecución

```bash
mvn spring-boot:run
```

El servicio queda disponible en `http://localhost:3000`.

## Autenticación

Todas las rutas bajo `/api/**`, excepto el login, requieren un token JWT.

**Obtener un token:**
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"usuario":"admin","clave":"admin123"}'
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

Una petición sin token, o con uno inválido o expirado, responde `401 Unauthorized`.

## Enrutamiento

El Gateway reenvía cada petición al microservicio correspondiente según el
prefijo de la ruta, preservando método HTTP, query params y body:

| Prefijo | Microservicio destino |
|---|---|
| `/api/estudiantes/**` | Estudiantes |
| `/api/materias/**` | Materias |
| `/api/inscripciones/**` | Inscripciones |

Si el microservicio responde con un error propio (400, 404, 409, etc.), ese mismo
código y cuerpo se devuelven sin modificar. Si el microservicio no responde
(caído, tiempo de espera agotado), el Gateway responde `502 Bad Gateway`:

```json
{ "error": "Servicio no disponible: http://localhost:3001" }
```

## Endpoint de composición

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

## Decisiones de diseño

- **RestTemplate en lugar de Spring Cloud Gateway.** El enrutamiento y la
  composición se implementan a mano sobre `RestTemplate`, lo que mantiene el
  flujo de cada petición explícito y fácil de seguir.
- **JWT sin Spring Security completo.** Un filtro (`OncePerRequestFilter`) valida
  el token en cada petición. Es suficiente para el alcance del laboratorio, sin
  la configuración adicional que implicaría una integración completa con Spring
  Security.
- **JSON genérico (`JsonNode`) en el endpoint de composición.** En lugar de DTOs
  tipados, el endpoint trabaja directamente con la estructura JSON que devuelve
  cada microservicio, lo que permite adaptarse a cambios en los contratos de los
  módulos sin recompilar clases adicionales.
