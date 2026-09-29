# Plan de trabajo — JPQL Console y SQL JDBC ejecutado

## Objetivo

Convertir la presentación pública de la consola de HQL a JPQL y agregar una vista de diagnóstico que muestre, después de cada ejecución, las sentencias SQL y parámetros que realmente llegaron por JDBC a la base.

El objetivo pedagógico y funcional es hacer visible esta cadena:

```text
Entidad y relación JPA
        ↓
consulta JPQL
        ↓
SQL real ejecutado por JDBC
        ↓
parámetros reales
        ↓
filas devueltas por la base
```

La implementación usará `datasource-proxy`; no se implementará un proxy JDBC propio.

---

## Alcance

### Entra

- Renombrar la interfaz y documentación pública de HQL a JPQL.
- Capturar SQL y parámetros reales de las ejecuciones iniciadas desde la consola.
- Mostrar una nueva pestaña de resultados: `SQL ejecutado`.
- Mantener la solapa existente de SQL nativo.
- Aplicar la captura tanto a JPQL como a comandos propios de la consola que terminen ejecutando JDBC.
- Aislar la captura: no mostrar consultas de otros requests de la aplicación.
- Mantener compatibilidad funcional con las consultas existentes.

### No entra

- Crear un proxy JDBC propio.
- Convertir SQL a JPQL o modificar el SQL capturado.
- Interpolar valores dentro del texto SQL reemplazando `?`.
- Soporte especial para múltiples proveedores JPA en esta fase.
- `EXPLAIN`, plan de ejecución, cancelación de consultas o análisis de rendimiento.
- Refactorizar nombres internos de paquetes, artefactos Maven o clases Java.
- Renombrar la ruta actual `/hqlconsole` en esta entrega.

---

## Decisiones de diseño

### Nombre público

La marca visible pasa a ser:

```text
JPQL Console
```

La pestaña principal pasa de `HQL` a `JPQL`.

La documentación debe explicar:

- JPQL es el lenguaje estándar de consultas JPA.
- `DESC`, `LIMIT`, `SELECT *`, `INSERT ... VALUES` y scripts son extensiones de la consola.
- Si el proveedor acepta sintaxis adicional, queda fuera del contrato portable de JPQL.

Los nombres técnicos existentes (`hql-console`, `/hqlconsole`, clases `Hql...`) permanecen por ahora. El cambio de artefactos, paquetes y URL se evalúa en una versión mayor, no junto con esta funcionalidad.

### Captura JDBC

Se incorporará `datasource-proxy` como dependencia del starter.

La consola no leerá:

- `show_sql`;
- salida estándar;
- logs de la aplicación;
- logs globales de parámetros.

En cambio, `datasource-proxy` decorará el `DataSource` usado por JPA y un listener recibirá los eventos JDBC estructurados: SQL, parámetros, tiempo, error y tipo de sentencia. [Documentación de datasource-proxy](https://jdbc-observations.github.io/datasource-proxy/docs/current/user-guide/)

### Aislamiento por ejecución

El proxy verá todas las consultas de la aplicación, pero sólo guardará las que ocurran durante una ejecución de consola.

Se implementará un contexto temporal:

```text
Inicio de ejecución de consola
  → crear colector vacío
  → asociarlo al hilo actual
  → ejecutar JPQL / comando
  → recoger SQL y parámetros JDBC
  → desligar el colector, incluso ante error
```

El listener JDBC consultará ese contexto. Si no existe, ignorará la consulta.

La primera versión asume la ejecución síncrona actual de la consola. Consultas iniciadas manualmente en otros hilos quedan fuera de alcance.

### SQL y parámetros

No se construirá un SQL falso reemplazando `?` por valores. Cada sentencia se mostrará separada de sus parámetros:

```text
select l1_0.id, l1_0.titulo
from libros l1_0
where l1_0.autor_id=?

Parámetros
1 · BIGINT · 42
```

Esto evita representaciones incorrectas de `NULL`, fechas, UUID, binarios, escapes y tipos JDBC.

---

## Modelo de respuesta

Agregar al resultado HTTP un campo aditivo:

```json
{
  "executedSql": [
    {
      "statement": "select l1_0.id, l1_0.titulo from libros l1_0 where l1_0.autor_id=?",
      "parameters": [
        {
          "position": 1,
          "jdbcType": "BIGINT",
          "value": "42"
        }
      ],
      "elapsedMs": 4,
      "success": true
    }
  ]
}
```

Reglas:

- `executedSql` siempre existe; puede ser una lista vacía.
- El orden es el orden real de ejecución JDBC.
- Una ejecución puede producir varias sentencias.
- El SQL nativo escrito por el usuario también puede aparecer, con sus parámetros y duración.
- Si falla una sentencia JDBC, se conserva en la lista con `success=false` y su error técnico.
- Los resultados, headers, filas y errores existentes no cambian.

---

## Fases

### Fase 0 — Línea base y contrato

1. Ejecutar la suite existente y `verify-demo.ps1`.
2. Registrar la cantidad actual de pruebas en verde.
3. Documentar la semántica de nombres:
   - `JPQL`: consultas portables.
   - `SQL`: SQL nativo de sólo lectura.
   - `Comandos de consola`: `DESC`, scripts e inserciones propias.
4. Confirmar que la demo usa un único `DataSource`.

**Puerta:** no comenzar cambios si la línea base no está verde.

### Fase 1 — Renombrado visible a JPQL

Archivos previstos:

- `README.md`
- `MANUAL-RAPIDO.md`
- `docs/help.md`
- `hql-console.html`
- mensajes visibles del backend

Tareas:

1. Cambiar el título visible a `JPQL Console`.
2. Cambiar la pestaña principal de `HQL` a `JPQL`.
3. Cambiar textos de ayuda, botones, mensajes y documentación.
4. Mantener el comportamiento actual del endpoint.
5. Mantener temporalmente el campo JSON `hql` por compatibilidad.
6. Aceptar también `jpql` como nombre nuevo del campo; si ambos aparecen, `jpql` tiene prioridad.

**Puerta:** la interfaz no muestra “HQL” como nombre de lenguaje público; las consultas existentes siguen funcionando.

### Fase 2 — Dependencia e instalación temprana del `DataSource` proxy

Separar la auto-configuración actual en dos responsabilidades:

```text
JdbcCaptureAutoConfiguration
    → decora el DataSource antes de construir el EntityManagerFactory

HqlConsoleAutoConfiguration
    → página, controller, runner y propiedades de consola
```

Tareas:

1. Agregar `datasource-proxy` como dependencia transitiva del starter.
2. Crear la propiedad:

```yaml
hql-console:
  jdbc-capture:
    enabled: true
```

3. Registrar un decorador de `DataSource` después de la autoconfiguración del datasource y antes de la autoconfiguración JPA.
4. Verificar mediante una prueba que el `EntityManagerFactory` obtiene conexiones a través del datasource decorado.
5. Preservar `unwrap()`, cierre de conexiones, pool, metadata JDBC y transacciones.
6. Si la decoración automática no es segura para un tipo de datasource, fallar al arrancar con un mensaje claro, no con captura parcial silenciosa.

**Puerta:** una consulta JPA simple dispara un evento de `datasource-proxy`.

### Fase 3 — Contexto y colector por ejecución de consola

Crear componentes internos:

```text
ConsoleJdbcCaptureContext
ConsoleJdbcCollector
ConsoleJdbcQueryListener
ExecutedSql
ExecutedSqlParameter
```

Tareas:

1. `ConsoleJdbcCaptureContext` conserva una pila por hilo de colectores activos.
2. La pila permite ejecuciones anidadas sin mezclar capturas.
3. El listener de `datasource-proxy` pregunta por el colector activo.
4. Si no hay colector, no almacena ni loguea nada.
5. Si lo hay, registra:
   - texto SQL;
   - parámetros y posiciones;
   - duración;
   - éxito o error;
   - tipo JDBC: `Statement`, `PreparedStatement` o batch.
6. El contexto se cierra obligatoriamente en un `finally`.

**Puerta:** dos consultas concurrentes de consola no mezclan SQL ni parámetros.

### Fase 4 — Integración con el runner y el endpoint

Tareas:

1. El controller abre una captura antes de delegar al runner.
2. Ejecuta el camino actual de JPQL, SQL nativo o comandos de consola.
3. Cierra la captura incluso si el runner lanza una excepción.
4. Adjunta `executedSql` al `HqlResult`.
5. Para errores, devuelve el SQL ya capturado sin cambiar el formato principal de error.
6. No cambiar el comportamiento de `allow-writes`, `dryRun`, límites de filas ni transacciones.

Cobertura:

| Operación | ¿Se captura? |
|---|---|
| JPQL `SELECT` | Sí |
| JPQL `UPDATE` / `DELETE` | Sí |
| `INSERT` propio de la consola | Sí |
| Script compuesto | Sí, lista ordenada |
| SQL nativo de lectura | Sí, como diagnóstico de JDBC |
| `DESC` de mappings | No necesariamente |
| Metadata JDBC | No se presenta como SQL ejecutado |

**Puerta:** el resultado funcional de cada operación es idéntico al anterior y suma sólo `executedSql`.

### Fase 5 — Pestaña de resultados “SQL ejecutado”

Agregar subpestañas en el panel derecho:

```text
Resultado | SQL ejecutado | JSON
```

Tareas:

1. La pestaña `Resultado` conserva la grilla actual.
2. La pestaña `SQL ejecutado` muestra una tarjeta por sentencia.
3. Cada tarjeta contiene:
   - número de orden;
   - SQL conservando saltos de línea;
   - duración;
   - estado;
   - lista de parámetros.
4. Agregar botón “Copiar SQL”.
5. Si no hubo SQL capturado, mostrar:

```text
No se ejecutó ninguna sentencia JDBC para esta operación.
```

6. Recordar la última subpestaña elegida mediante `localStorage`.
7. Escapar siempre SQL, errores y valores antes de insertarlos en HTML.

**Puerta:** la página sigue pasando `node --check` y no pierde ninguna interacción existente.

### Fase 6 — Pruebas

Pruebas unitarias:

- El colector conserva orden, SQL, parámetros y duración.
- No hay captura fuera de un contexto activo.
- El contexto se limpia tras éxito y tras excepción.
- La pila soporta anidamiento.
- Los DTO se serializan correctamente.
- El renderizador HTML escapa `<`, `>`, comillas y valores con saltos de línea.

Pruebas de integración:

- `SELECT` JPQL simple devuelve al menos una sentencia SQL.
- Un `JOIN` JPQL devuelve SQL con el join real.
- Un `INSERT` propio captura los JDBC generados.
- Un script devuelve múltiples sentencias ordenadas.
- SQL nativo también devuelve el SQL ejecutado y parámetros.
- Dos requests simultáneos no se contaminan.
- Una consulta común de la aplicación, fuera de la consola, no aparece en la respuesta.
- Una excepción JDBC conserva la captura disponible y limpia el contexto.
- `hql-console.jdbc-capture.enabled=false` mantiene la consola funcionando con `executedSql=[]`.

Pruebas end-to-end:

- Extender `verify-demo.ps1`.
- Verificar que la respuesta JSON contiene `executedSql`.
- Verificar una sentencia con parámetros concretos.
- Verificar que el HTML incluye la pestaña `SQL ejecutado`.
- Verificar persistencia de la subpestaña.
- Ejecutar con y sin `context-path`.

**Puerta:** toda la regresión está verde; no se elimina ni debilita ningún test existente.

### Fase 7 — Documentación y cierre

Actualizar:

- `README.md`
- `MANUAL-RAPIDO.md`
- `docs/help.md`
- `PENDIENTES.md`

Agregar ejemplos:

```jpql
SELECT l
FROM Libro l
JOIN l.autor a
WHERE a.nombre = 'Borges'
```

Y explicar que la pestaña SQL muestra:

```text
La sentencia JDBC que realmente ejecutó la aplicación
más los parámetros asignados a cada placeholder ?.
```

Documentar también:

- la captura es de desarrollo;
- no usa ni requiere `show_sql`;
- no se registran consultas ajenas a la ejecución de consola;
- los valores de parámetros pueden contener información sensible;
- no se debe copiar y ejecutar SQL interpolado como si fuera una sentencia exacta.

---

## Riesgos y mitigación

| Riesgo | Mitigación |
|---|---|
| El datasource decorado no es el usado por JPA | Prueba de integración que verifica el evento JDBC al ejecutar JPQL. |
| Se capturan consultas ajenas | Contexto por hilo, activado sólo durante el endpoint de consola. |
| Se filtran valores sensibles | Mostrar parámetros sólo en la consola local; preparar redacción configurable en una fase posterior. |
| El proxy afecta pooling o `unwrap()` | Pruebas sobre H2 y preservación de delegación JDBC. |
| El SQL contiene varias sentencias inesperadas | Mostrar lista ordenada, no asumir una sentencia única. |
| Se confunde JPQL con comandos propios | Documentación y etiquetas separadas en la interfaz. |
| Se rompe compatibilidad del endpoint | Aceptar `hql` legado y `jpql` nuevo. |

---

## Criterios de aceptación

1. La interfaz pública se llama `JPQL Console`.
2. La consulta principal se presenta como JPQL.
3. La consola sigue ejecutando todo lo que ejecutaba antes.
4. `datasource-proxy` intercepta JDBC sin usar logs globales.
5. Sólo se devuelven consultas originadas durante una ejecución de consola.
6. Cada ejecución devuelve SQL, parámetros, duración y estado.
7. La interfaz tiene una pestaña `SQL ejecutado`.
8. Los valores no se interpolan dentro del SQL.
9. La captura soporta resultados, mutaciones y scripts.
10. La consola funciona si se desactiva la captura.
11. Todas las pruebas actuales y nuevas terminan en verde.