# Plan prudente: extraer la página HTML de `HqlConsolePage`

## Objetivo y límite

Mover el HTML, CSS y JavaScript embebidos en `HqlConsolePage.java` a un recurso HTML incluido en el JAR. La respuesta HTTP seguirá siendo generada por `HqlConsoleController`, para inyectar la configuración necesaria por solicitud (en especial la ruta efectiva que incluye el `context-path`).

No se incorporará un motor de templates, un framework de frontend, una CDN ni un endpoint de configuración adicional. No se cambiará el contrato HTTP, la URL de la consola, ni el comportamiento funcional visible.

## Regla innegociable de avance

**Cada cambio, incluso uno pequeño, debe ser verificable mediante un test automatizado o una comprobación automatizada concreta. No se inicia la fase siguiente hasta que el cambio de la fase actual y todos los tests preexistentes estén en verde.**

Si una prueba falla, se detiene el plan: se diagnostica y corrige dentro de la misma fase, o se revierte el cambio local. No se compensa un rojo continuando con fases posteriores.

La suite de referencia es:

- `gradlew.bat --console=plain build`
- `verify-demo.ps1`
- `verify-demo.ps1 -ContextPath /demo -MaxRows 3`

La última prueba cubre simultáneamente `context-path`, sustitución de la ruta base y el límite de filas. Si Node está disponible, `verify-demo.ps1` también comprueba que el JavaScript entregado al navegador se pueda parsear y ejecuta las funciones puras extraídas de la página real.

## Fase 0 — Línea base inalterada

**Cambio:** ninguno.

1. Inspeccionar el estado inicial del árbol de trabajo y no tocar cambios ajenos.
2. Ejecutar las tres verificaciones de referencia.
3. Registrar sus resultados (comando, fecha y resultado) en la salida de la tarea o en el PR, sin alterar el código.

**Criterio de entrada a la fase 1:** las tres comprobaciones terminan en verde. Si alguna ya falla, el trabajo de extracción queda bloqueado: primero debe resolverse o aceptarse explícitamente esa falla preexistente.

## Fase 1 — Prueba de presencia del recurso, sin cambiar la respuesta

**Cambio mínimo:** agregar un HTML de prueba, pequeño y no usado todavía, en:

`hql-console-starter/src/main/resources/thejavalistener/hqlconsole/web/hql-console.html`

No se modifica aún `HqlConsolePage`, el controller ni la página que recibe el navegador.

**Nueva comprobación testeable:** añadir un test unitario que cargue el recurso desde el classpath y compruebe:

- que existe;
- que se decodifica como UTF-8;
- que contiene los marcadores de plantilla acordados.

El test debe fallar si el recurso no entra en el JAR o se renombra por accidente.

**Comprobación adicional:** inspeccionar el JAR generado para confirmar que el recurso se empaquetó en la ruta esperada.

**Puerta de salida:** el nuevo test, `gradlew.bat --console=plain build` y los dos `verify-demo.ps1` deben estar en verde. Recién entonces se continúa.

## Fase 2 — Definir el contrato de inyección y sus pruebas

**Cambio mínimo:** definir en el HTML exactamente dos marcadores de configuración:

- base de la consola: ruta con `context-path` y `hql-console.path`;
- `maxRows`.

La configuración se inyectará como JSON seguro dentro de un único bloque de configuración. No se interpolarán valores directamente en sentencias JavaScript.

`ALLOW_WRITES` no se migrará: hoy se inyecta pero no se consume en el cliente. La autorización de escrituras sigue siendo responsabilidad exclusiva del servidor.

**Nuevos tests unitarios antes del renderer:** cubrir el serializador/escapador que se usará para la configuración con:

- una base ordinaria;
- `context-path` no vacío;
- comillas, barras invertidas, saltos de línea y caracteres `<`, `>` y `&`;
- valores numéricos de `maxRows`.

La expectativa es que el resultado sea JSON válido y que ningún valor pueda cerrar o alterar el bloque `<script>`.

**Puerta de salida:** tests nuevos y existentes en verde. No se altera todavía la respuesta de la consola.

## Fase 3 — Extraer el HTML usando el contenido efectivamente entregado

**Cambio mínimo:** reemplazar el contenido del recurso de prueba por el HTML completo de la consola, conservando el resultado que hoy recibe el navegador.

La fuente de verdad para esta migración es el HTML servido por la versión actual, no la copia literal del text block Java. Esto evita introducir barras invertidas duplicadas: el Java actual necesita escapes adicionales que un archivo `.html` no necesita.

1. Trasladar HTML, CSS y JavaScript al recurso.
2. Sustituir los valores dinámicos por los dos marcadores definidos en fase 2.
3. Mantener los marcadores `// INICIO funciones puras` y `// FIN funciones puras`, requeridos por `verify-demo.ps1`.
4. No reorganizar estilos, ni reformatear JavaScript, ni introducir cambios de interfaz en esta fase.

**Nuevas comprobaciones:**

- test del recurso: presencia única de cada marcador y presencia de los elementos/fragmentos críticos que ya inspecciona `verify-demo.ps1`;
- extracción del script del recurso y `node --check`, cuando Node esté disponible.

**Puerta de salida:** las comprobaciones nuevas y toda la suite preexistente en verde. Se compara además la página entregada por la versión todavía vigente con el recurso, ignorando sólo los dos valores dinámicos previstos.

## Fase 4 — Implementar el renderer de recursos sin cambiar el controller

**Cambio mínimo:** convertir `HqlConsolePage` en un cargador/renderizador pequeño:

1. Cargar el recurso desde el classpath con UTF-8 una sola vez.
2. Validar al cargar que cada marcador existe exactamente una vez; fallar con un error descriptivo si el empaquetado o la plantilla son inválidos.
3. Sustituir los marcadores con la configuración JSON segura de fase 2.
4. Mantener la firma actual `html(String base, int maxRows, boolean allowWrites)` temporalmente, aunque `allowWrites` quede sin uso, para no combinar esta refactorización con un cambio de API interna.

El recurso no se expondrá desde `static/` ni se servirá directamente: será leído desde el JAR por el renderer. El controller conserva los encabezados `Cache-Control: no-store` y `Pragma: no-cache`.

**Nuevos tests unitarios del renderer:**

- salida correcta para base sin `context-path`;
- salida correcta para base con `context-path`;
- `maxRows` inyectado como número;
- HTML sin marcadores residuales;
- falla clara cuando falta el recurso o un marcador (mediante una variante de plantilla inyectable para test).

**Puerta de salida:** todos los tests unitarios, `build` y ambos `verify-demo.ps1` en verde. Es especialmente obligatorio comprobar que la página servida contiene la base esperada para `''` y `/demo`.

## Fase 5 — Limpieza mínima y consolidación

**Cambio mínimo:** eliminar `TEMPLATE_PARTE_1`, `TEMPLATE_PARTE_2`, `TEMPLATE` y el método de escape obsoleto. Mantener o ajustar únicamente el Javadoc para describir que la plantilla vive como recurso UTF-8.

En esta fase se puede retirar el parámetro `allowWrites` de `HqlConsolePage.html` sólo si se actualizan todos sus usos y existe una prueba que confirme que la política de escrituras sigue bloqueándose en el endpoint. Si no aporta claridad suficiente, se mantiene: la limpieza opcional no justifica riesgo.

Actualizar README y comentarios de `verify-demo.ps1` que indiquen que el JavaScript vive en un text block; deben explicar que está en el recurso HTML y que los escapes son los propios de JS/CSS.

**Nuevas comprobaciones:**

- búsqueda estática que confirme que no quedó HTML de la consola embebido en Java;
- inspección del JAR: contiene el HTML y ya no contiene una clase inflada por constantes de plantilla;
- prueba del endpoint con `hql-console.allow-writes=false`, si no existía ya, para preservar la garantía de seguridad del servidor.

**Puerta de salida final:** todos los tests nuevos y todos los preexistentes, incluidos los dos escenarios de `verify-demo.ps1`, en verde. Revisar el diff para confirmar que sólo contiene la extracción, el renderer, pruebas y documentación asociada.

## Fase 6 — Verificación manual acotada

Sólo después de superar todas las puertas automáticas:

1. Abrir la demo en navegador con y sin `context-path`.
2. Probar Ctrl+Enter, HQL, SQL, `DESC`, panel de entidades, divisores y persistencia en `localStorage`.
3. Recargar la página y confirmar que `no-store` permite ver la versión actual.

Esta fase no sustituye los tests automatizados. Si revela un defecto, se agrega primero una prueba automatizada que lo reproduzca; después se corrige dentro de la fase afectada y se repite toda la suite.

## Criterio de finalización

La tarea termina únicamente cuando:

- el HTML completo vive en `src/main/resources` y se empaqueta dentro del starter;
- la respuesta conserva la inyección segura de `base` y `maxRows`;
- no se añadió infraestructura de frontend ni dependencias nuevas;
- cada componente nuevo tiene pruebas específicas;
- toda la suite existente y toda la suite añadida están en verde en el estado final.
