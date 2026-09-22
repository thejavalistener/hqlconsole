package thejavalistener.hqlconsole.web;

/**
 * La página de la consola: un único HTML con CSS y JS embebidos.
 *
 * <p>Sin template engine, sin build de frontend y sin CDN: el jar tiene que funcionar aunque la
 * máquina esté sin internet. Los valores que se inyectan son de configuración, no de usuario.</p>
 */
public final class HqlConsolePage
{
	private HqlConsolePage()
	{
	}

	/**
	 * El HTML de la consola.
	 *
	 * <p>El {@code base} es lo único de la ruta que la página necesita: se usa para armar las
	 * llamadas al endpoint. La ruta ya no se muestra en pantalla, así que no se pasa aparte.</p>
	 */
	public static String html(String base,int maxRows,boolean allowWrites)
	{
		return TEMPLATE
				.replace("__BASE__",_jsString(base))
				.replace("__MAX_ROWS__",String.valueOf(maxRows))
				.replace("__ALLOW_WRITES__",allowWrites?"true":"false");
	}

	/** El valor va dentro de un literal JS entre comillas simples. */
	private static String _jsString(String value)
	{
		return value.replace("\\","\\\\").replace("'","\\'");
	}

	private static final String TEMPLATE =
		"""
		<!DOCTYPE html>
		<html lang="es">
		<head>
		<meta charset="utf-8">
		<meta name="viewport" content="width=device-width, initial-scale=1">
		<title>HQL Console</title>
		<style>
		  :root { color-scheme: light dark; --borde:#d0d4da; --fondo:#f6f7f9; --acento:#1f6feb; --error:#b3261e; }
		  * { box-sizing: border-box; }
		  /* El atributo hidden tiene que ganar siempre. Sin esto, un panel con display:flex en su
		     propia regla de CSS se queda visible aunque el JS le ponga hidden: es el mismo nivel de
		     cascada y el id pesa mas que la regla del navegador. */
		  [hidden] { display:none !important; }
		  html, body { height:100%; }
		  body { margin:0; padding:12px; font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
		         background:var(--fondo); display:flex; flex-direction:column; gap:8px; height:100vh; overflow:hidden; }
		  body.arrastrando { user-select:none; cursor:col-resize; }
		  h1 { font-size:16px; margin:0; }
		  .barra { display:flex; align-items:baseline; gap:10px; flex-wrap:wrap; flex:0 0 auto; }
		  button { padding:7px 16px; font-size:13px; font-weight:600; color:#fff; background:var(--acento); border:0; border-radius:6px; cursor:pointer; }
		  button:disabled { opacity:.5; cursor:progress; }
		  .estado { font-size:12px; opacity:.75; }
		  .sel { color:var(--acento); opacity:1; font-weight:600; }
		  #error { display:none; flex:0 0 auto; max-height:30vh; overflow:auto; padding:10px;
		           border:1px solid var(--error); border-left-width:4px; border-radius:6px;
		           background:#fff5f4; color:var(--error); font-size:13px; }
		  #error pre { margin:6px 0 0; font-family: ui-monospace, Consolas, monospace; font-size:12px; white-space:pre-wrap; }

		  /* --- el área partida: editor a la izquierda, resultados a la derecha --- */
		  #split { flex:1 1 auto; min-height:0; display:flex; align-items:stretch; --ancho-editor:48%; }
		  #panel-editor { flex:0 0 var(--ancho-editor); display:flex; flex-direction:column; gap:8px; min-width:0; }
		  #hql { flex:1 1 auto; width:100%; min-height:0; margin:0; padding:10px; background:#fff;
		         font-family: ui-monospace, Consolas, monospace; font-size:13px; line-height:1.5;
		         border:1px solid var(--borde); border-radius:6px; resize:none; tab-size:2;
		         /* Sin wrap: una línea larga scrollea en horizontal en vez de partirse. El wrap="off"
		            del HTML es el que manda (el atributo gana sobre el CSS en algunos navegadores), pero
		            se declara también acá para que la regla se lea sola. */
		         wrap:off; white-space:pre; overflow-x:auto; overflow-y:auto; }
		  /* La barra del pie del editor: el aviso de alcance a la izquierda, el botón a la derecha. */
		  .pie-editor { flex:0 0 auto; display:flex; align-items:center; gap:8px; min-width:0; }
		  .pie-editor .alcance { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
		  .pie-editor #run { margin-left:auto; flex:0 0 auto; }
		  #divisor { flex:0 0 10px; display:flex; align-items:center; justify-content:center;
		             cursor:col-resize; touch-action:none; background:none; border:0; padding:0; }
		  #divisor::before { content:''; width:3px; height:100%; border-radius:2px; background:var(--borde);
		                     transition:background .12s ease; }
		  #divisor:hover::before, #divisor:focus-visible::before, body.arrastrando #divisor::before { background:var(--acento); }
		  #divisor:focus-visible { outline:2px solid var(--acento); outline-offset:2px; border-radius:4px; }
		  #panel-resultado { flex:1 1 auto; min-width:0; min-height:0; display:flex; flex-direction:column; gap:8px; }
		  .vacio { flex:0 0 auto; padding:16px; text-align:center; color:#8b949e; font-size:13px;
		           border:1px dashed var(--borde); border-radius:6px; }
		  .tabla { flex:1 1 auto; min-height:0; overflow:auto; border:1px solid var(--borde); border-radius:6px; background:#fff; }
		  table { border-collapse:collapse; width:100%; font-size:13px; }
		  th, td { padding:6px 10px; text-align:left; border-bottom:1px solid var(--borde); font-family: ui-monospace, Consolas, monospace; white-space:pre; }
		  th { position:sticky; top:0; background:#eceff3; font-weight:600; }
		  tr:nth-child(even) td { background:#fafbfc; }
		  td.nulo { color:#8b949e; font-style:italic; }

		  /* Los headers se pueden clickear para ordenar. La flechita va como contenido del ::after
		     para no ensuciar el textContent del th, que es el nombre de la columna. */
		  th.ordenable { cursor:pointer; user-select:none; }
		  th.ordenable:hover { background:#e2e7ee; }
		  th.ordenable::after { content:''; opacity:.35; margin-left:6px; }
		  th.ordenable:hover::after { content:'\\21C5'; opacity:.6; }
		  th.orden-asc::after { content:'\\2191'; opacity:1; }
		  th.orden-desc::after { content:'\\2193'; opacity:1; }
		  .pie { flex:0 0 auto; font-size:12px; opacity:.7; }
		  details { flex:0 0 auto; font-size:12px; }
		  summary { cursor:pointer; opacity:.7; }
		  details pre { background:#fff; border:1px solid var(--borde); border-radius:6px; padding:10px; overflow:auto; max-height:40vh; }

		  /* --- el detalle de una entidad: el panel derecho se parte en dos --- */
		  body.arrastrando-alto { user-select:none; cursor:row-resize; }
		  #divisor-h { flex:0 0 10px; display:flex; align-items:center; justify-content:center;
		               cursor:row-resize; touch-action:none; background:none; border:0; padding:0; }
		  #divisor-h::before { content:''; height:3px; width:100%; border-radius:2px; background:var(--borde);
		                       transition:background .12s ease; }
		  #divisor-h:hover::before, #divisor-h:focus-visible::before, body.arrastrando-alto #divisor-h::before { background:var(--acento); }
		  #divisor-h:focus-visible { outline:2px solid var(--acento); outline-offset:2px; border-radius:4px; }
		  #panel-detalle { flex:0 0 var(--alto-detalle,45%); min-height:0; display:flex; flex-direction:column; gap:8px; }
		  #detalle-error { flex:0 0 auto; padding:8px 10px; border:1px solid var(--error); border-left-width:4px;
		                   border-radius:6px; background:#fff5f4; color:var(--error); font-size:12px; }
		  /* Las filas de la lista de "DESC" se pueden clickear. El !important es por el rayado de las pares. */
		  .fila-clickeable { cursor:pointer; }
		  .fila-clickeable:hover td { background:#eef4ff !important; }
		  .fila-elegida td { background:#dce8ff !important; font-weight:600; }

		  /* --- el panel lateral: la lista de entidades, para no tener que escribir DESC a mano --- */
		  /* Angosto a proposito: solo tiene que entrar el nombre de la entidad. Cerrado queda una
		     franja con el boton, que es el control para volver a abrirlo. */
		  #panel-entidades { flex:0 0 auto; width:150px; margin-right:8px; min-width:0;
		                     display:flex; flex-direction:column; overflow:hidden;
		                     background:#fff; border:1px solid var(--borde); border-radius:6px; }
		  #panel-entidades.contraido { width:30px; }
		  #entidades-cabecera { flex:0 0 auto; display:flex; align-items:center; gap:4px;
		                        padding:4px 4px 4px 8px; border-bottom:1px solid var(--borde); }
		  #panel-entidades.contraido #entidades-cabecera { padding:4px; border-bottom:0; }
		  .entidades-titulo { flex:1 1 auto; min-width:0; font-size:12px; font-weight:600;
		                      overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
		  #panel-entidades.contraido .entidades-titulo { display:none; }
		  #toggle-entidades { flex:0 0 auto; padding:0 4px; font-size:14px; line-height:1.2; font-weight:700;
		                      color:var(--acento); background:none; border:0; border-radius:4px; cursor:pointer; }
		  #toggle-entidades:hover { background:#eef4ff; }
		  #lista-entidades { flex:1 1 auto; min-height:0; overflow:auto; padding:4px;
		                     display:flex; flex-direction:column; gap:2px; }
		  #panel-entidades.contraido #lista-entidades { display:none; }
		  .entidad-item { text-align:left; padding:4px 6px; font-size:12px; font-weight:400; color:inherit;
		                  font-family: ui-monospace, Consolas, monospace; background:none; border:0;
		                  border-radius:4px; cursor:pointer; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
		  .entidad-item:hover { background:#eef4ff; }
		  .entidad-item.elegida { background:#dce8ff; font-weight:600; }

		  /* --- el menú contextual (botón derecho sobre una entidad) --- */
		  #menu { position:fixed; z-index:50; min-width:190px; padding:4px;
		          background:#fff; border:1px solid var(--borde); border-radius:6px;
		          box-shadow:0 6px 20px rgba(0,0,0,.18); }
		  .menu-item { display:block; width:100%; text-align:left; padding:6px 10px;
		               font-size:13px; font-weight:400; color:inherit; background:none; border:0;
		               border-radius:4px; cursor:pointer; white-space:nowrap; }
		  .menu-item:hover { background:#eef4ff; }
		  .menu-titulo { padding:4px 10px 6px; font-size:11px; opacity:.6;
		                 border-bottom:1px solid var(--borde); margin-bottom:4px;
		                 white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }

		  /* En pantallas angostas el divisor no tiene sentido: se apilan. */
		  @media (max-width: 720px) {
		    body { height:auto; overflow:auto; }
		    #split { flex-direction:column; }
		    #panel-editor { flex:1 1 auto; }
		    #hql { min-height:200px; }
		    #divisor { display:none; }
		    #panel-resultado { flex:1 1 auto; }
		    .tabla { max-height:60vh; }
		    /* El detalle se apila abajo, sin divisor: se ve entero o se scrollea. */
		    #divisor-h { display:none; }
		    #panel-detalle { flex:0 0 auto; }
		    /* El panel de entidades no entra en una pantalla angosta: se esconde entero. */
		    #panel-entidades { display:none; }
		  }
		</style>
		</head>
		<body>
		<div class="barra">
		  <h1>HQL Console</h1>
		</div>
		<div id="error"><div id="error-msg"></div><pre id="error-sql"></pre></div>
		<div id="split">
		  <aside id="panel-entidades">
		    <div id="entidades-cabecera">
		      <span class="entidades-titulo">Entidades</span>
		      <button type="button" id="toggle-entidades" aria-expanded="true"
		              title="Contraer el panel de entidades">&#171;</button>
		    </div>
		    <div id="lista-entidades"></div>
		  </aside>
		  <div id="panel-editor">
		    <textarea id="hql" spellcheck="false" wrap="off" placeholder="SELECT e.id, e.nombre FROM Empleado e"></textarea>
		    <div class="pie-editor">
		      <span class="estado" id="pista">Ctrl+Enter:</span>
		      <span class="estado sel alcance" id="seleccion"></span>
		      <button id="run" title="Ctrl+Enter">Ejecutar</button>
		    </div>
		  </div>
		  <div id="divisor" role="separator" aria-orientation="vertical" tabindex="0" aria-label="Redimensionar el editor"
		       title="Arrastra para redimensionar. Flechas: de a 2%. Inicio/Fin: extremos. Doble clic: 50/50."></div>
		  <div id="panel-resultado">
		    <span class="estado" id="estado"></span>
		    <div class="vacio" id="vacio">Los resultados aparecen acá.</div>
		    <div class="tabla" id="tabla" hidden><table id="t"></table></div>
		    <div id="divisor-h" role="separator" aria-orientation="horizontal" tabindex="0" hidden
		         aria-label="Redimensionar el detalle"
		         title="Arrastra para redimensionar el detalle. Flechas: de a 2%. Inicio/Fin: extremos. Doble clic: 45%."></div>
		    <div id="panel-detalle" hidden>
		      <span class="estado" id="detalle-titulo"></span>
		      <div id="detalle-error" hidden></div>
		      <div class="tabla" id="tabla-detalle" hidden><table id="t-detalle"></table></div>
		    </div>
		    <div class="pie" id="pie"></div>
		    <details id="crudo" hidden><summary>JSON crudo</summary><pre id="crudo-pre"></pre></details>
		  </div>
		</div>
		<div id="menu" hidden>
		  <div class="menu-titulo" id="menu-titulo"></div>
		  <button type="button" class="menu-item" id="menu-insert">Generar INSERT</button>
		  <button type="button" class="menu-item" id="menu-select">SELECT *</button>
		</div>
		<script>
		const BASE = '__BASE__';
		const MAX_ROWS = __MAX_ROWS__;
		const ALLOW_WRITES = __ALLOW_WRITES__;

		const ta = document.getElementById('hql');
		const btn = document.getElementById('run');
		const estado = document.getElementById('estado');
		const seleccion = document.getElementById('seleccion');
		const cajaError = document.getElementById('error');
		const errorMsg = document.getElementById('error-msg');
		const errorSql = document.getElementById('error-sql');
		const cajaTabla = document.getElementById('tabla');
		const tabla = document.getElementById('t');
		const vacio = document.getElementById('vacio');
		const pie = document.getElementById('pie');
		const crudo = document.getElementById('crudo');
		const crudoPre = document.getElementById('crudo-pre');
		const split = document.getElementById('split');
		const divisor = document.getElementById('divisor');
		const panelEditor = document.getElementById('panel-editor');
		const panelResultado = document.getElementById('panel-resultado');
		const divisorAlto = document.getElementById('divisor-h');
		const panelDetalle = document.getElementById('panel-detalle');
		const detalleTitulo = document.getElementById('detalle-titulo');
		const detalleError = document.getElementById('detalle-error');
		const cajaDetalle = document.getElementById('tabla-detalle');
		const tablaDetalle = document.getElementById('t-detalle');
		const panelEntidades = document.getElementById('panel-entidades');
		const toggleEntidades = document.getElementById('toggle-entidades');
		const listaEntidades = document.getElementById('lista-entidades');
		const menu = document.getElementById('menu');
		const menuTitulo = document.getElementById('menu-titulo');
		const menuInsert = document.getElementById('menu-insert');
		const menuSelect = document.getElementById('menu-select');

		const EJEMPLO = 'SELECT e.id, e.nombre, e.salario FROM Empleado e';
		const CLAVE_TEXTO = 'hql-console.consulta';
		const CLAVE_ANCHO = 'hql-console.ancho';
		const CLAVE_ALTO = 'hql-console.alto-detalle';
		const CLAVE_ENTIDADES = 'hql-console.entidades-abierto';

		// Los nombres de entidad que ya vimos (salen del DESC sin argumentos). Sirven para saber si el
		// "TIPO JAVA" de un atributo es una entidad relacionada o un tipo común como String o Long, y
		// para armar el panel lateral.
		let entidadesConocidas = null;

		// La entidad que el panel lateral muestra como elegida. Se guarda el nombre aunque la lista
		// todavía no esté dibujada: así, cuando llegue, la fila ya sale marcada.
		let entidadElegida = null;

		// localStorage puede tirar (modo privado, cookies de sitio bloqueadas). Si eso pasa, el
		// script entero se caía y con él los listeners: la consola quedaba muerta sin decir por qué.
		// Con este envoltorio nunca tira y, si no hay almacén, al menos dura lo que dura la pestaña.
		const ALMACEN = (function() {
		  try {
		    const prueba = 'hql-console.prueba';
		    window.localStorage.setItem(prueba, '1');
		    window.localStorage.removeItem(prueba);
		    return window.localStorage;
		  } catch (e) {
		    const memoria = new Map();
		    return {
		      getItem: function(k) { return memoria.has(k) ? memoria.get(k) : null; },
		      setItem: function(k, v) { memoria.set(k, String(v)); },
		      removeItem: function(k) { memoria.delete(k); }
		    };
		  }
		})();

		function guardarTexto() {
		  try { ALMACEN.setItem(CLAVE_TEXTO, ta.value); } catch (e) {}
		}

		// Lo que haya guardado se restituye al abrir la página: sobrevive a recargar y a reiniciar
		// la aplicación, porque vive en el navegador y no en el servidor.
		const textoGuardado = ALMACEN.getItem(CLAVE_TEXTO);
		ta.value = (textoGuardado === null || textoGuardado === undefined) ? EJEMPLO : textoGuardado;

		// Se guarda mientras se escribe (con retardo) y también al ejecutar y al cerrar: así no se
		// pierde lo escrito aunque nunca se llegue a ejecutar.
		let temporizador = null;
		ta.addEventListener('input', function() {
		  clearTimeout(temporizador);
		  temporizador = setTimeout(guardarTexto, 400);
		});
		window.addEventListener('pagehide', guardarTexto);
		document.addEventListener('visibilitychange', function() {
		  if (document.visibilityState === 'hidden') { guardarTexto(); }
		});

		// INICIO funciones puras: sin DOM ni estado, así verify-demo.ps1 las corre tal cual en Node.
		// Lo que se ejecuta cuando hay texto pintado: la selección, y nada más.
		function textoAejecutar(valor, inicio, fin) {
		  if (inicio < fin) { return valor.substring(inicio, fin); }
		  return valor;
		}

		// El texto, partido en líneas con sus offsets. Una línea en blanco (o con sólo espacios) es
		// la frontera entre párrafos. Se conserva el salto de línea final implícito: un texto que
		// termina en salto tiene una última línea vacía, y eso es correcto.
		//
		// OJO al editar este archivo: esto vive dentro de un text block de Java, así que una barra
		// invertida en el código de abajo hay que escribirla doble. Con una sola, Java la convierte
		// en un salto de línea o un retorno de carro de verdad dentro del literal de JS y rompe la
		// página entera (el error se ve recién en el navegador, no al compilar).
		function lineasDe(texto) {
		  const salida = [];
		  let i = 0;
		  for (;;) {
		    const j = texto.indexOf('\\n', i);
		    const fin = (j === -1) ? texto.length : j;
		    salida.push({ inicio: i, fin: fin, blanco: !texto.substring(i, fin).trim() });
		    if (j === -1) { return salida; }
		    i = j + 1;
		  }
		}

		// El párrafo donde está el cursor: desde la línea en blanco de arriba (o el inicio del
		// textarea) hasta la de abajo (o el final). Si el cursor cae en una línea en blanco no hay
		// párrafo propio, así que se usa el de arriba y, si no hay, el de abajo.
		function rangoParrafo(texto, posicion) {
		  const lineas = lineasDe(texto);
		  const p = Math.max(0, Math.min(texto.length, posicion));

		  let indice = 0;
		  for (let i = 0; i < lineas.length; i++) { if (lineas[i].inicio <= p) { indice = i; } else { break; } }

		  if (lineas[indice].blanco) {
		    let arriba = -1, abajo = -1;
		    for (let i = indice - 1; i >= 0; i--) { if (!lineas[i].blanco) { arriba = i; break; } }
		    if (arriba === -1) {
		      for (let i = indice + 1; i < lineas.length; i++) { if (!lineas[i].blanco) { abajo = i; break; } }
		    }
		    if (arriba === -1 && abajo === -1) { return { inicio: p, fin: p }; }
		    indice = (arriba === -1) ? abajo : arriba;
		  }

		  let primero = indice, ultimo = indice;
		  while (primero > 0 && !lineas[primero - 1].blanco) { primero--; }
		  while (ultimo + 1 < lineas.length && !lineas[ultimo + 1].blanco) { ultimo++; }

		  return { inicio: lineas[primero].inicio, fin: lineas[ultimo].fin };
		}

		// El texto del alert de un INSERT. Cuando hubo varias sentencias se aclara en cuántas fueron,
		// que es lo que permite ver de un vistazo que ninguna se cortó mal al partir por punto y coma.
		function mensajeInsercion(filas, sentencias) {
		  const cuantas = (sentencias === undefined || sentencias === null) ? 1 : sentencias;
		  const encabezado = (filas === 1 ? 'Se insertó 1 fila' : 'Se insertaron ' + filas + ' filas');
		  return cuantas > 1 ? (encabezado + ' en ' + cuantas + ' sentencias') : encabezado;
		}

		// "DESC" a secas (o "describe") es la lista de entidades: esa grilla es la única clickeable
		// por fila, porque cada fila es una entidad. La selección del editor puede venir con
		// espacios o con mayúsculas, así que se normaliza.
		function esDescSinArgumentos(hql) {
		  const t = hql.trim().toLowerCase();
		  return t === 'desc' || t === 'describe';
		}

		// "DESC <Entidad>": el detalle de una entidad. Ahí lo clickeable son los atributos que
		// apuntan a otra entidad. El nombre sale de la misma función que usa el panel lateral.
		function esDescDeUnaEntidad(hql) {
		  return entidadDeDesc(hql) !== null;
		}

		// El nombre de la entidad de un "DESC <Entidad>" (o "describe"), o null si la sentencia es
		// otra cosa. Es una sola entidad y nada más: "DESC Libro li" no es un DESC válido.
		// (Las barras invertidas van dobles por el text block de Java.)
		function entidadDeDesc(hql) {
		  const encontrado = /^(?:desc|describe)\\s+(\\S+)$/i.exec(hql.trim());
		  return encontrado ? encontrado[1] : null;
		}

		// UPDATE y DELETE se confirman antes de commitear; el resto se ejecuta de una.
		function pideConfirmacion(hql) {
		  const t = hql.trim().toLowerCase();
		  return t.indexOf('update') === 0 || t.indexOf('delete') === 0;
		}

		// --- el orden de la grilla (click en un header) ---
		// El tipo lo dice el backend, columna por columna: sin eso habría que adivinar mirando el
		// texto, y un número ordenado como texto da 10 antes que 9. Si no viene (o no coincide con la
		// cantidad de columnas), se ordena como texto, que es lo único honesto que se puede hacer.
		function tipoDeColumna(tipos, indice) {
		  if (!tipos || indice < 0 || indice >= tipos.length) { return 'TEXTO'; }
		  return String(tipos[indice] || 'TEXTO');
		}

		// Compara dos celdas del mismo tipo. Devuelve <0, 0 o >0, y NUNCA aplica la dirección: de eso
		// se encarga el que ordena, que necesita distinguir "los NULL van al final" (que no se
		// invierte) de "el resto se da vuelta" (que sí).
		function compararCeldas(a, b, tipo) {
		  const vacioA = (a === null || a === undefined);
		  const vacioB = (b === null || b === undefined);
		  if (vacioA && vacioB) { return 0; }
		  if (vacioA) { return 1; }
		  if (vacioB) { return -1; }

		  if (tipo === 'NUMERO') {
		    const na = Number(a);
		    const nb = Number(b);
		    if (isFinite(na) && isFinite(nb)) { return na - nb; }
		  }
		  if (tipo === 'BOOLEANO') {
		    const ba = (a === true || a === 'true') ? 1 : 0;
		    const bb = (b === true || b === 'true') ? 1 : 0;
		    if (ba !== bb) { return ba - bb; }
		  }
		  if (tipo === 'FECHA') {
		    // El backend manda las fechas como texto ISO, y así el orden alfabético ya es el
		    // cronológico. Igual se comparan como fechas cuando se puede, para no depender de eso.
		    const fa = Date.parse(a);
		    const fb = Date.parse(b);
		    if (!isNaN(fa) && !isNaN(fb)) { return fa - fb; }
		  }
		  // Alfabético, y nada más: si la columna es TEXTO, "10" va antes que "9", que es lo que
		  // significa ordenar texto. Con sensitivity:'base' las mayúsculas y los acentos no parten la
		  // lista en dos (Ana y ana son la misma palabra a los fines de ordenar).
		  return String(a).localeCompare(String(b), undefined, { sensitivity: 'base' });
		}

		// El signo que pone la dirección: ascendente multiplica por 1, descendente invierte.
		function factorDeDireccion(direccion) {
		  return direccion === 'desc' ? -1 : 1;
		}

		/**
		 * Ordena las filas por una columna y devuelve una lista NUEVA (no toca la que recibe).
		 *
		 * <p>Es una copia a propósito: las filas originales quedan guardadas tal como llegaron del
		 * servidor, así que volver a ordenar por otra columna siempre parte del mismo lugar y el
		 * orden que pidió la consulta no se va perdiendo de a poco con cada click.</p>
		 *
		 * <p>La dirección se aplica sólo a las celdas con valor: los NULL van <b>siempre al final</b>,
		 * también en descendente. Si se invirtiera todo, ordenar al revés arrancaría con una pantalla
		 * llena de NULL y el dato que uno fue a buscar quedaría al fondo.</p>
		 */
		function ordenarFilas(filas, indice, tipo, direccion) {
		  const copia = (filas || []).slice();
		  const factor = factorDeDireccion(direccion);
		  copia.sort(function(a, b) {
		    const va = a[indice];
		    const vb = b[indice];
		    const vacioA = (va === null || va === undefined);
		    const vacioB = (vb === null || vb === undefined);
		    if (vacioA || vacioB) {
		      if (vacioA && vacioB) { return 0; }
		      return vacioA ? 1 : -1; // el NULL va al final, en las dos direcciones
		    }
		    return factor * compararCeldas(va, vb, tipo);
		  });
		  return copia;
		}

		// La dirección siguiente del orden: si ya estaba ascendente, el próximo click baja.
		function direccionSiguiente(direccionActual) {
		  return direccionActual === 'asc' ? 'desc' : 'asc';
		}

		// --- el menú contextual de una entidad (las sentencias que genera) ---
		// Cuántas filas trae el [SELECT *] del menú. 100 entra cómodo en pantalla y no castiga con un
		// click accidental; si el tope global (max-rows) es menor, gana ese.
		//
		// Va acá adentro, con las funciones puras, a propósito: es una constante que usan, y el bloque
		// marcado es lo que verify-demo.ps1 extrae para correr en Node.
		const LIMITE_MENU = 100;

		// El atributo del id lleva un "*" pegado (lo pone el DESC), así que hay que sacarlo para
		// escribir nombres de columna de verdad.
		function sinMarcaDeId(nombre) {
		  return String(nombre).replace(/\\*$/, '');
		}

		function esAtributoId(nombre) {
		  return /\\*$/.test(String(nombre));
		}

		/**
		 * El INSERT de ejemplo de una entidad, a partir de las filas de su DESC.
		 *
		 * <p>El id se excluye: es el que genera la base, así que ponerlo a mano choca con la
		 * secuencia. Las relaciones van por su id (un número), y el resto por un literal del tipo que
		 * corresponda —texto entre comillas, número pelado, fecha ISO, booleano— para que la
		 * sentencia se pueda ejecutar tal cual después de completarla.</p>
		 */
		function insertDeEntidad(entidad, filasDesc) {
		  const columnas = [];
		  const valores = [];
		  (filasDesc || []).forEach(function(fila) {
		    const atributo = fila[0];
		    const tipoJava = String(fila[1] || '');
		    const relacion = String(fila[4] || '-');
		    if (esAtributoId(atributo)) { return; }
		    columnas.push(sinMarcaDeId(atributo));
		    valores.push(valorDeEjemplo(tipoJava, relacion));
		  });
		  return '// Completa y ejecuta esta sentencia\\n'
		       + 'INSERT INTO ' + entidad + ' (' + columnas.join(',') + ') VALUES (' + valores.join(',') + ');';
		}

		/**
		 * Un valor de ejemplo acorde a la columna del DESC.
		 *
		 * <p>El caso que importa es la <b>relación</b>: su TIPO JAVA es el nombre de una clase
		 * (`Departamento`), que se ve igual que un String, así que no alcanza con mirarlo. La columna
		 * RELACION del DESC dice cuál es el id de la entidad apuntada y de qué tipo, y con eso el
		 * valor sale sin comillas cuando el id es numérico. Sin ese dato (un DESC viejo, o una consulta
		 * a mano) se cae al tipo Java, que para una relación da texto entre comillas.</p>
		 */
		function valorDeEjemplo(tipoJava, relacion) {
		  if (relacion && relacion !== '-') {
		    // "Entidad#id (Tipo)" -> el tipo del id decide si lleva comillas.
		    const tipoId = /\\(([^)]*)\\)\\s*$/.exec(relacion);
		    return tipoId && esTipoNumerico(tipoId[1]) ? '999' : "'999'";
		  }
		  if (esTipoNumerico(tipoJava)) {
		    return '999';
		  }
		  if (/^(Boolean|boolean)$/.test(tipoJava)) {
		    return 'false';
		  }
		  if (/^(LocalDate)$/.test(tipoJava)) {
		    return "'2024-01-01'";
		  }
		  if (/^(LocalDateTime|Instant|OffsetDateTime|ZonedDateTime|Date|Timestamp)$/.test(tipoJava)) {
		    return 'NOW';
		  }
		  return "'999'";
		}

		/** Los tipos que van pelados en la sentencia, sin comillas. */
		function esTipoNumerico(tipo) {
		  return /^(Integer|int|Long|long|Short|short|Byte|byte|BigInteger|BigDecimal|Double|double|Float|float)$/.test(String(tipo));
		}


		// El "SELECT *" del menú: siempre con límite, para que un click no traiga una tabla entera.
		function selectDeEntidad(entidad) {
		  return 'SELECT * FROM ' + entidad + ' LIMIT ' + LIMITE_MENU;
		}

		/**
		 * Dónde meter el INSERT generado: en el párrafo donde está el cursor, no encima de todo lo
		 * que había escrito.
		 *
		 * <p>Se deja una línea en blanco antes y después, así el bloque nuevo queda separado de lo que
		 * había (y sigue siendo su propio párrafo para el Ctrl+Enter). Y el cursor queda adentro del
		 * <b>primer paréntesis</b> de la sentencia, que en un INSERT es el de las columnas: ahí es
		 * donde se completan los valores.</p>
		 *
		 * <p>Devuelve el texto nuevo, la posición del cursor y el rango del bloque insertado (que es
		 * lo que se selecciona después, para que se vea qué se agregó).</p>
		 */
		function insertarEnParrafo(texto, cursor, sentencia) {
		  const parrafo = rangoParrafo(texto, cursor);
		  const antes = texto.substring(0, parrafo.fin);
		  const despues = texto.substring(parrafo.fin);

		  // Los separadores se calculan mirando los saltos que YA hay, no agregando a ciegas: entre
		  // dos párrafos el hueco ya aporta sus saltos, y sumarle "\\n\\n" dejaba cuatro líneas en
		  // blanco en vez de una. Se completa lo que falta hasta tener una línea en blanco.
		  const sepAntes = antes.trim().length > 0 ? _saltosQueFaltan(antes, 'antes') : '';
		  const sepDespues = despues.trim().length > 0 ? _saltosQueFaltan(despues, 'despues') : '';

		  // Con el separador ya calculado, la sentencia arranca después de los espacios que se
		  // descartan: si no, el rango seleccionado incluiría saltos de línea de más.
		  const inicio = parrafo.fin + sepAntes.length - _espaciosAlFinal(antes);
		  const nuevo = antes + sepAntes + sentencia + sepDespues + despues;
		  // El cursor va justo después del primer paréntesis de la sentencia, que en un INSERT es el
		  // de las columnas: ahí es donde se completan los valores.
		  const abre = sentencia.indexOf('(');
		  const posicion = inicio + (abre < 0 ? sentencia.length : abre + 1);

		  return { texto: nuevo, cursor: posicion, seleccion: { inicio: inicio, fin: inicio + sentencia.length } };
		}

		// Cuántos saltos hay que agregar para dejar UNA línea en blanco de ese lado. Si ya hay dos o
		// más, no se agrega ninguno (el hueco que había se usa tal cual).
		function _saltosQueFaltan(fragmento, lado) {
		  const cola = lado === 'antes' ? /(\\n[ \\t]*)*$/.exec(fragmento)[0] : /^[ \\t]*(\\n[ \\t]*)*/.exec(fragmento)[0];
		  const saltos = (cola.match(/\\n/g) || []).length;
		  return saltos >= 2 ? '' : '\\n'.repeat(2 - saltos);
		}

		// Los espacios (no los saltos) que quedan al final del texto de arriba: el separador se pega
		// después de ellos, así que el inicio real de la sentencia los saltea.
		function _espaciosAlFinal(fragmento) {
		  return /[ \\t]*$/.exec(fragmento)[0].length;
		}

		// El texto del confirm(). El número es la alarma: si esperabas 1 fila y dice 4, cancelás.
		// (Las barras invertidas van dobles por el text block de Java.)
		function mensajeConfirmacion(hql, filas, truncado) {
		  const accion = (hql.trim().toLowerCase().indexOf('delete') === 0) ? 'borrar' : 'modificar';
		  const cuantas = (filas === 1) ? '1 fila' : (filas + ' filas');
		  let texto = 'Se van a ' + accion + ' ' + cuantas + '.\\n\\n¿Confirmás?';
		  if (truncado) { texto += '\\n\\n(se alcanzó el tope de filas: el resto NO se toca)'; }
		  return texto;
		}
		// FIN funciones puras

		// Qué se manda al servidor: la selección si tiene texto; si no, el párrafo del cursor. Sólo
		// se ejecuta todo el textarea cuando todo el textarea es un único párrafo.
		function rangoAEjecutar() {
		  const inicio = ta.selectionStart, fin = ta.selectionEnd;
		  const recorte = textoAejecutar(ta.value, inicio, fin);
		  if (fin > inicio && recorte.trim()) {
		    // Ya estaba seleccionado por el usuario: no se toca la selección al terminar.
		    return { hql: recorte, etiqueta: 'sólo la selección', inicio: inicio, fin: fin, pintar: false };
		  }
		  const parrafo = rangoParrafo(ta.value, inicio);
		  return { hql: ta.value.substring(parrafo.inicio, parrafo.fin), etiqueta: 'el párrafo del cursor',
		           inicio: parrafo.inicio, fin: parrafo.fin, pintar: true };
		}

		// Deja pintado el párrafo que se ejecutó, para que se vea qué se corrió (y para poder volver a
		// correr lo mismo con Ctrl+Enter sin apuntar de nuevo con el cursor). Sólo cuando la sentencia
		// salió del párrafo: si el usuario ya tenía algo seleccionado, su selección se respeta.
		function pintarRango(rango) {
		  if (!rango || !rango.pintar) { return; }
		  ta.focus();
		  ta.setSelectionRange(rango.inicio, rango.fin);
		}

		// El aviso de alcance que se ve al pie del editor: qué se va a ejecutar con Ctrl+Enter.
		function refrescarSeleccion() {
		  const inicio = ta.selectionStart, fin = ta.selectionEnd;
		  const caracteres = Math.max(0, fin - inicio);
		  let cantidad, leyenda;
		  if (caracteres > 0 && ta.value.substring(inicio, fin).trim()) {
		    cantidad = caracteres;
		    leyenda = 'se ejecutará sólo la selección';
		  } else {
		    const parrafo = rangoParrafo(ta.value, inicio);
		    cantidad = ta.value.substring(parrafo.inicio, parrafo.fin).trim().length;
		    leyenda = 'se ejecutará el párrafo del cursor';
		  }
		  seleccion.textContent = leyenda + ' (' + cantidad + ' caracteres)';
		}

		btn.addEventListener('click', ejecutar);
		// Sin esto, el mousedown del botón le roba el foco al textarea y el navegador colapsa la
		// selección: el botón terminaba ejecutando todo en vez de lo pintado.
		btn.addEventListener('mousedown', function(ev) { ev.preventDefault(); });
		ta.addEventListener('keydown', function(ev) {
		  if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); ejecutar(); }
		});
		['keyup', 'mouseup', 'select', 'input', 'click'].forEach(function(evento) {
		  ta.addEventListener(evento, refrescarSeleccion);
		});
		document.addEventListener('selectionchange', function() {
		  if (document.activeElement === ta) { refrescarSeleccion(); }
		});
		refrescarSeleccion();

		// ==================== el divisor movible ====================
		const ANCHO_MIN = 15, ANCHO_MAX = 85, ANCHO_POR_DEFECTO = 48;

		function limitar(pct) { return Math.max(ANCHO_MIN, Math.min(ANCHO_MAX, pct)); }

		function aplicarAncho(pct, persistir) {
		  const valor = limitar(pct);
		  split.style.setProperty('--ancho-editor', valor + '%');
		  divisor.setAttribute('aria-valuenow', String(Math.round(valor)));
		  divisor.setAttribute('aria-valuemin', String(ANCHO_MIN));
		  divisor.setAttribute('aria-valuemax', String(ANCHO_MAX));
		  if (persistir) { try { ALMACEN.setItem(CLAVE_ANCHO, String(valor)); } catch (e) {} }
		  return valor;
		}

		let anchoActual = ANCHO_POR_DEFECTO;
		const anchoGuardado = parseFloat(ALMACEN.getItem(CLAVE_ANCHO));
		anchoActual = aplicarAncho(isNaN(anchoGuardado) ? ANCHO_POR_DEFECTO : anchoGuardado, false);

		let arrastrando = false;

		function anchoSegunPuntero(clientX) {
		  const caja = split.getBoundingClientRect();
		  if (caja.width <= 0) { return anchoActual; }
		  // El editor arranca después del panel de entidades: midiendo desde el borde del split, el
		  // divisor quedaría corrido todo el ancho del panel. El porcentaje sí es sobre el split
		  // entero, porque el --ancho-editor es un % del split (el flex-basis del panel del editor).
		  const editor = panelEditor.getBoundingClientRect();
		  const inicio = editor.width > 0 ? editor.left : caja.left;
		  return ((clientX - inicio) / caja.width) * 100;
		}

		divisor.addEventListener('pointerdown', function(ev) {
		  arrastrando = true;
		  try { divisor.setPointerCapture(ev.pointerId); } catch (e) {}
		  document.body.classList.add('arrastrando');
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), false);
		  ev.preventDefault();
		});
		divisor.addEventListener('pointermove', function(ev) {
		  if (!arrastrando) { return; }
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), false);
		});
		function soltar(ev) {
		  if (!arrastrando) { return; }
		  arrastrando = false;
		  document.body.classList.remove('arrastrando');
		  try { divisor.releasePointerCapture(ev.pointerId); } catch (e) {}
		  anchoActual = aplicarAncho(anchoSegunPuntero(ev.clientX), true);
		}
		divisor.addEventListener('pointerup', soltar);
		divisor.addEventListener('pointercancel', function() {
		  arrastrando = false;
		  document.body.classList.remove('arrastrando');
		});
		divisor.addEventListener('dblclick', function() { anchoActual = aplicarAncho(ANCHO_POR_DEFECTO, true); });
		divisor.addEventListener('keydown', function(ev) {
		  const paso = ev.shiftKey ? 10 : 2;
		  if (ev.key === 'ArrowLeft') { anchoActual = aplicarAncho(anchoActual - paso, true); }
		  else if (ev.key === 'ArrowRight') { anchoActual = aplicarAncho(anchoActual + paso, true); }
		  else if (ev.key === 'Home') { anchoActual = aplicarAncho(ANCHO_MIN, true); }
		  else if (ev.key === 'End') { anchoActual = aplicarAncho(ANCHO_MAX, true); }
		  else { return; }
		  ev.preventDefault();
		});

		// ==================== el divisor del detalle (horizontal) ====================
		// Mismo comportamiento que el divisor vertical, pero moviendo el alto del detalle. Se
		// mantiene separado del otro a propósito: el vertical ya está andando y probado a mano.
		const ALTO_MIN = 15, ALTO_MAX = 85, ALTO_POR_DEFECTO = 45;

		function limitarAlto(pct) { return Math.max(ALTO_MIN, Math.min(ALTO_MAX, pct)); }

		function aplicarAlto(pct, persistir) {
		  const valor = limitarAlto(pct);
		  panelResultado.style.setProperty('--alto-detalle', valor + '%');
		  divisorAlto.setAttribute('aria-valuenow', String(Math.round(valor)));
		  divisorAlto.setAttribute('aria-valuemin', String(ALTO_MIN));
		  divisorAlto.setAttribute('aria-valuemax', String(ALTO_MAX));
		  if (persistir) { try { ALMACEN.setItem(CLAVE_ALTO, String(valor)); } catch (e) {} }
		  return valor;
		}

		let altoActual = ALTO_POR_DEFECTO;
		const altoGuardado = parseFloat(ALMACEN.getItem(CLAVE_ALTO));
		altoActual = aplicarAlto(isNaN(altoGuardado) ? ALTO_POR_DEFECTO : altoGuardado, false);

		let arrastrandoAlto = false;

		function altoSegunPuntero(clientY) {
		  const caja = panelResultado.getBoundingClientRect();
		  if (caja.height <= 0) { return altoActual; }
		  // Se mide desde el borde de abajo: agarrar el divisor y subir agranda el detalle.
		  return ((caja.bottom - clientY) / caja.height) * 100;
		}

		divisorAlto.addEventListener('pointerdown', function(ev) {
		  arrastrandoAlto = true;
		  try { divisorAlto.setPointerCapture(ev.pointerId); } catch (e) {}
		  document.body.classList.add('arrastrando-alto');
		  altoActual = aplicarAlto(altoSegunPuntero(ev.clientY), false);
		  ev.preventDefault();
		});
		divisorAlto.addEventListener('pointermove', function(ev) {
		  if (!arrastrandoAlto) { return; }
		  altoActual = aplicarAlto(altoSegunPuntero(ev.clientY), false);
		});
		function soltarAlto(ev) {
		  if (!arrastrandoAlto) { return; }
		  arrastrandoAlto = false;
		  document.body.classList.remove('arrastrando-alto');
		  try { divisorAlto.releasePointerCapture(ev.pointerId); } catch (e) {}
		  altoActual = aplicarAlto(altoSegunPuntero(ev.clientY), true);
		}
		divisorAlto.addEventListener('pointerup', soltarAlto);
		divisorAlto.addEventListener('pointercancel', function() {
		  arrastrandoAlto = false;
		  document.body.classList.remove('arrastrando-alto');
		});
		divisorAlto.addEventListener('dblclick', function() { altoActual = aplicarAlto(ALTO_POR_DEFECTO, true); });
		divisorAlto.addEventListener('keydown', function(ev) {
		  const paso = ev.shiftKey ? 10 : 2;
		  if (ev.key === 'ArrowUp') { altoActual = aplicarAlto(altoActual + paso, true); }
		  else if (ev.key === 'ArrowDown') { altoActual = aplicarAlto(altoActual - paso, true); }
		  else if (ev.key === 'Home') { altoActual = aplicarAlto(ALTO_MIN, true); }
		  else if (ev.key === 'End') { altoActual = aplicarAlto(ALTO_MAX, true); }
		  else { return; }
		  ev.preventDefault();
		});

		// ==================== el panel de entidades ====================
		// La lista de entidades como atajo: clickear una equivale a ejecutar "DESC <Entidad>". El
		// panel se contrae y se expande con el botoncito de la cabecera, y el estado se recuerda.
		function aplicarEntidades(abierto, persistir) {
		  panelEntidades.classList.toggle('contraido', !abierto);
		  // Las flechitas van escapadas: el JS vive en un text block de Java.
		  toggleEntidades.textContent = abierto ? '\u00ab' : '\u00bb';
		  toggleEntidades.setAttribute('aria-expanded', abierto ? 'true' : 'false');
		  toggleEntidades.title = abierto ? 'Contraer el panel de entidades' : 'Expandir el panel de entidades';
		  if (persistir) { try { ALMACEN.setItem(CLAVE_ENTIDADES, abierto ? '1' : '0'); } catch (e) {} }
		}

		aplicarEntidades(ALMACEN.getItem(CLAVE_ENTIDADES) !== '0', false);
		toggleEntidades.addEventListener('click', function() {
		  // Si estaba contraido, esto lo abre; si estaba abierto, lo contrae.
		  aplicarEntidades(panelEntidades.classList.contains('contraido'), true);
		});

		// Dibuja (o redibuja) la lista con los nombres que ya conocemos. Se puede llamar varias veces.
		function pintarEntidades() {
		  const nombres = entidadesConocidas ? Array.from(entidadesConocidas).sort() : [];
		  listaEntidades.textContent = '';
		  nombres.forEach(function(nombre) {
		    const boton = document.createElement('button');
		    boton.type = 'button';
		    boton.className = 'entidad-item';
		    boton.textContent = nombre;
		    boton.title = 'DESC ' + nombre + '  (botón derecho: más acciones)';
		    boton.addEventListener('click', function() { abrirEntidad(nombre); });
		    boton.addEventListener('contextmenu', function(ev) {
		      ev.preventDefault();
		      abrirMenu(ev.clientX, ev.clientY, nombre);
		    });
		    listaEntidades.appendChild(boton);
		  });
		  marcarEntidadElegida(entidadElegida);
		}

		// ==================== el menú contextual ====================
		// Acciones sobre una entidad sin escribir nada: generar el INSERT de ejemplo o correr un
		// SELECT * acotado. El menú se cierra con click en cualquier lado, con Escape, con scroll o
		// al elegir una opción. Vive en el body y se posiciona con coordenadas de pantalla.
		let entidadDelMenu = null;

		function abrirMenu(x, y, entidad) {
		  entidadDelMenu = entidad;
		  menuTitulo.textContent = entidad;
		  menu.hidden = false;

		  // Si el menú no entra en la pantalla, se corre hacia adentro: si no, aparece cortado.
		  const caja = menu.getBoundingClientRect();
		  const ancho = caja.width, alto = caja.height;
		  const x2 = Math.max(4, Math.min(x, window.innerWidth - ancho - 4));
		  const y2 = Math.max(4, Math.min(y, window.innerHeight - alto - 4));
		  menu.style.left = x2 + 'px';
		  menu.style.top = y2 + 'px';
		}

		function cerrarMenu() {
		  menu.hidden = true;
		  entidadDelMenu = null;
		}

		document.addEventListener('click', function(ev) {
		  if (!menu.hidden && !menu.contains(ev.target)) { cerrarMenu(); }
		});
		document.addEventListener('keydown', function(ev) {
		  if (ev.key === 'Escape') { cerrarMenu(); }
		});
		// El scroll de la lista o de la grilla dejaría el menú flotando lejos de su entidad.
		window.addEventListener('scroll', cerrarMenu, true);
		window.addEventListener('resize', cerrarMenu);

		// [Generar INSERT]: NO ejecuta. Escribe la sentencia en el editor para que la completes y la
		// corras vos. Necesita el DESC de la entidad para saber sus columnas y sus tipos, así que se
		// pide al backend (es la misma lectura que hace el panel de detalle).
		menuInsert.addEventListener('click', async function() {
		  const entidad = entidadDelMenu;
		  cerrarMenu();
		  if (!entidad) { return; }
		  estado.textContent = 'Armando el INSERT de ' + entidad + '...';
		  const respuesta = await pedir('DESC ' + entidad, false);
		  if (!respuesta.ok || !respuesta.datos.rows) {
		    mostrarError(respuesta.datos || { error: 'No se pudo leer el DESC de ' + entidad });
		    return;
		  }
		  insertarEnEditor(insertDeEntidad(entidad, respuesta.datos.rows));
		  estado.textContent = 'INSERT de ' + entidad + ' escrito abajo del párrafo del cursor: completá los valores y ejecutá.';
		});

		// [SELECT *]: se ejecuta sin pasar por el editor. El LIMIT va en la sentencia, así que el
		// número que se ve en el pie es el que se usó de verdad.
		menuSelect.addEventListener('click', function() {
		  const entidad = entidadDelMenu;
		  cerrarMenu();
		  if (!entidad) { return; }
		  marcarEntidadElegida(entidad);
		  ejecutarTexto(selectDeEntidad(entidad), 'el menú de ' + entidad);
		});

		// Poner el INSERT generado en el editor, en el párrafo del cursor: lo que había escrito no se
		// pierde. Después queda seleccionado el bloque insertado, para ver de un vistazo qué se agregó.
		function insertarEnEditor(sentencia) {
		  const puesto = insertarEnParrafo(ta.value, ta.selectionStart, sentencia);
		  ta.value = puesto.texto;
		  ta.focus();
		  ta.setSelectionRange(puesto.seleccion.inicio, puesto.seleccion.fin);
		  // El cursor, en cambio, queda listo para escribir: adentro del primer paréntesis.
		  ta.setSelectionRange(puesto.cursor, puesto.cursor);
		  guardarTexto();
		  refrescarSeleccion();
		}

		// Click en una entidad: equivale a escribir "DESC <Entidad>" y ejecutar. No se toca el editor
		// a propósito: el panel es un atajo para mirar, no para pisar lo que estabas escribiendo.
		function abrirEntidad(nombre) {
		  marcarEntidadElegida(nombre);
		  ejecutarTexto('DESC ' + nombre, 'el panel de entidades');
		}

		function marcarEntidadElegida(nombre) {
		  entidadElegida = nombre;
		  const items = listaEntidades.querySelectorAll('.entidad-item');
		  items.forEach(function(item) {
		    item.classList.toggle('elegida', item.textContent === nombre);
		  });
		}

		// Lo que se acaba de ejecutar: si es un "DESC <Entidad>", el panel marca esa entidad.
		function sincronizarEntidad(hql) {
		  marcarEntidadElegida(entidadDeDesc(hql));
		}

		// ==================== ejecutar ====================
		function ejecutar() {
		  // Se guarda el contenido del editor, no lo último ejecutado: es el estado del textarea.
		  guardarTexto();
		  const rango = rangoAEjecutar();
		  // Queda pintado lo que se ejecutó: se ve de un vistazo qué corrió, y el próximo Ctrl+Enter
		  // corre exactamente lo mismo.
		  pintarRango(rango);
		  // Un pegado desde Windows puede traer CRLF: el retorno de carro sobra y Hibernate no lo
		  // necesita. (Barra invertida doble por el text block de Java.)
		  ejecutarTexto(rango.hql.split('\\r').join('').trim(), rango.etiqueta);
		}

		// El trabajo de ejecutar, separado de "de dónde salió el texto". El panel lateral ejecuta un
		// DESC sin pasar por el textarea; de ahí la separación.
		async function ejecutarTexto(hql, etiqueta) {
		  if (!hql) {
		    mostrarError({ error: 'No hay nada que ejecutar: ni la selección ni el párrafo del cursor tienen texto.' });
		    return;
		  }
		  // Se decide acá si hay que avisar al terminar, sin depender de que el backend lo diga: lo
		  // único que importa es qué se pidió ejecutar.
		  const esInsercion = hql.toLowerCase().indexOf('insert') === 0;
		  const confirmar = pideConfirmacion(hql);
		  btn.disabled = true;
		  cajaError.style.display = 'none';
		  try {
		    // En UPDATE y DELETE, primero un dry-run: el servidor ejecuta, cuenta y tira atrás. Con
		    // ese número se pregunta; recién si se confirma se manda la sentencia de verdad.
		    if (confirmar) {
		      estado.textContent = 'Contando ' + etiqueta + ' (todavía sin tocar nada)...';
		      const prueba = await pedir(hql, true);
		      if (!prueba.ok) { mostrarError(prueba.datos); return; }
		      if (!confirm(mensajeConfirmacion(hql, prueba.datos.affectedRows, prueba.datos.truncated))) {
		        estado.textContent = 'Cancelado: no se modificó nada.';
		        return;
		      }
		    }
		    estado.textContent = 'Ejecutando ' + etiqueta + '...';
		    const respuesta = await pedir(hql, false);
		    if (!respuesta.ok) { mostrarError(respuesta.datos); return; }
		    // Toda sentencia nueva arranca con el panel derecho limpio: si estaba partido, se cierra
		    // el detalle, y se borra lo que hubiera quedado de la sentencia anterior. Va acá (y no
		    // antes del dry-run) para que cancelar la confirmación no borre el resultado que ya
		    // estabas mirando.
		    resetPanelDerecho();
		    const cabeceras = mostrarResultado(respuesta.datos);
		    // DML es una escritura sola; BATCH, varias en una transacción. Las dos avisan si son INSERT.
		    if (esInsercion && (respuesta.datos.type === 'DML' || respuesta.datos.type === 'BATCH')) {
		      alert(mensajeInsercion(respuesta.datos.affectedRows, respuesta.datos.statementCount));
		    }
		    // El DESC sin argumentos es la lista de entidades: cada fila abre su detalle abajo.
		    // El DESC de una entidad muestra sus atributos: los que son relaciones @ManyToOne abren
		    // el detalle de la entidad relacionada, también abajo.
		    if (esDescSinArgumentos(hql) && cabeceras) {
		      hacerListaClickeable(cabeceras);
		    } else if (esDescDeUnaEntidad(hql) && cabeceras) {
		      hacerRelacionesClickeables(cabeceras, tabla);
		    }
		    // El panel lateral marca la entidad que se está mirando (y la desmarca si la sentencia
		    // no era un DESC de una entidad).
		    sincronizarEntidad(hql);
		  } catch (e) {
		    mostrarError({ error: 'No se pudo contactar la consola: ' + e });
		  } finally {
		    btn.disabled = false;
		  }
		}

		// Un POST al endpoint. Devuelve {ok, datos}: ok es si el HTTP fue 2xx, y datos el JSON (o un
		// error armado a mano si el servidor no mandó JSON).
		async function pedir(hql, dryRun) {
		  const respuesta = await fetch(BASE + '/api/execute', {
		    method: 'POST',
		    headers: { 'Content-Type': 'application/json' },
		    body: JSON.stringify({ hql: hql, dryRun: dryRun })
		  });
		  let datos;
		  try {
		    datos = await respuesta.json();
		  } catch (e) {
		    datos = { error: 'El servidor respondio HTTP ' + respuesta.status + ' sin JSON.' };
		  }
		  return { ok: respuesta.ok, datos: datos };
		}

		function mostrarError(datos) {
		  errorMsg.textContent = datos.error || 'Error desconocido.';
		  errorSql.textContent = datos.cause ? ('causa: ' + datos.cause) : (datos.statement || '');
		  cajaError.style.display = 'block';
		  resetPanelDerecho();
		  estado.textContent = '';
		  pie.textContent = '';
		}

		// Dibuja una grilla en la caja y la tabla que le pasen, y devuelve las cabeceras que usó.
		// Lo usan la grilla de resultados y la del detalle: son la misma cosa.
		//
		// Las filas que recibe NO se tocan: se guardan como "originales" para que ordenar por otra
		// columna vuelva a partir de lo que mandó el servidor.
		function dibujarGrilla(caja, tabla, datos) {
		  const cabeceras = (datos.headers && datos.headers.length) ? datos.headers : cabecerasDeFilas(datos.rows);
		  const filas = datos.rows || [];
		  tabla.textContent = '';

		  const thead = document.createElement('thead');
		  const filaCabeceras = document.createElement('tr');
		  cabeceras.forEach(function(c, indice) {
		    const th = document.createElement('th');
		    th.textContent = c;
		    th.className = 'ordenable';
		    th.title = 'Ordenar por ' + c;
		    // El estado arranca sin orden: el primer click ordena ascendente y el segundo, descendente.
		    th.setAttribute('aria-sort', 'none');
		    th.addEventListener('click', function() {
		      ordenarPor(tabla, indice, cabeceras.length);
		    });
		    filaCabeceras.appendChild(th);
		  });
		  thead.appendChild(filaCabeceras);
		  tabla.appendChild(thead);

		  // El estado va colgado de la propia tabla: la grilla de resultados y la del detalle son dos
		  // tablas distintas y cada una ordena por su cuenta, sin variables globales que se pisen.
		  tabla.__filas = (datos.rows || []).slice();
		  tabla.__tipos = datos.types || [];
		  tabla.__orden = null;

		  pintarFilas(tabla, filas);
		  caja.hidden = false;
		  return cabeceras;
		}

		// Vuelca las filas en el tbody. Se llama al dibujar y cada vez que se ordena.
		function pintarFilas(tabla, filas) {
		  const viejo = tabla.querySelector('tbody');
		  if (viejo) { viejo.remove(); }

		  const tbody = document.createElement('tbody');
		  filas.forEach(function(fila) {
		    const tr = document.createElement('tr');
		    fila.forEach(function(celda) {
		      const td = document.createElement('td');
		      if (celda === null || celda === undefined) {
		        td.textContent = 'NULL';
		        td.className = 'nulo';
		      } else {
		        td.textContent = String(celda);
		      }
		      tr.appendChild(td);
		    });
		    tbody.appendChild(tr);
		  });
		  tabla.appendChild(tbody);
		}

		// Un click en un header: si es la misma columna, invierte la dirección; si es otra, empieza
		// ascendente por la nueva. Siempre parte de las filas originales.
		function ordenarPor(tabla, indice, columnas) {
		  const originales = tabla.__filas || [];
		  const anterior = tabla.__orden;
		  const direccion = (anterior && anterior.indice === indice) ? direccionSiguiente(anterior.direccion) : 'asc';
		  const tipo = tipoDeColumna(tabla.__tipos, indice);

		  pintarFilas(tabla, ordenarFilas(originales, indice, tipo, direccion));
		  tabla.__orden = { indice: indice, direccion: direccion };
		  marcarOrden(tabla, indice, direccion);
		  recablearFilas(tabla);
		}

		// La flechita y el resaltado del header activo. Se limpian los demás.
		function marcarOrden(tabla, indice, direccion) {
		  const celdas = tabla.querySelectorAll('thead th');
		  for (let i = 0; i < celdas.length; i++) {
		    const th = celdas[i];
		    th.classList.remove('orden-asc', 'orden-desc');
		    th.setAttribute('aria-sort', 'none');
		    if (i === indice) {
		      th.classList.add(direccion === 'desc' ? 'orden-desc' : 'orden-asc');
		      th.setAttribute('aria-sort', direccion === 'desc' ? 'descending' : 'ascending');
		    }
		  }
		}

		// Devuelve las cabeceras dibujadas, o nada si el resultado no era una grilla.
		function mostrarResultado(datos) {
		  crudoPre.textContent = JSON.stringify(datos, null, 2);
		  crudo.hidden = false;
		  vacio.hidden = true;

		  if (datos.type === 'DML' || datos.type === 'BATCH') {
		    cajaTabla.hidden = true;
		    pie.textContent = '';
		    const detalle = datos.message ? datos.message : (datos.affectedRows + ' fila(s) afectada(s)');
		    estado.textContent = detalle + ' en ' + datos.elapsedMs + ' ms';
		    return null;
		  }

		  const cabeceras = dibujarGrilla(cajaTabla, tabla, datos);

		  let resumen = datos.rowCount + ' fila' + (datos.rowCount === 1 ? '' : 's') + ' en ' + datos.elapsedMs + ' ms';
		  if (datos.truncated) { resumen = resumen + ' - truncado a ' + MAX_ROWS + ' filas'; }
		  if (datos.message) { resumen = resumen + ' - ' + datos.message; }
		  estado.textContent = resumen;
		  pie.textContent = ALLOW_WRITES
		    ? 'escrituras habilitadas (hql-console.allow-writes=true)'
		    : 'solo lectura (hql-console.allow-writes=false)';
		  return cabeceras;
		}

		// ==================== el detalle de una entidad ====================

		// La grilla de "DESC" a secas es la lista de entidades. Se busca la columna ENTIDAD (no la
		// tabla: "DESC" espera el nombre de la clase) y cada fila pasa a ser clickeable.
		// De paso queda sabido qué nombres son entidades, que es lo que permite después reconocer
		// las relaciones en el detalle sin pedirle nada nuevo al backend.
		function hacerListaClickeable(cabeceras) {
		  const columna = cabeceras.indexOf('ENTIDAD');
		  if (columna < 0) { return; }
		  const nombres = new Set();
		  const filas = tabla.querySelectorAll('tbody tr');
		  for (let i = 0; i < filas.length; i++) {
		    const fila = filas[i];
		    const celda = fila.cells[columna];
		    if (!celda) { continue; }
		    const entidad = celda.textContent;
		    nombres.add(entidad);
		    fila.classList.add('fila-clickeable');
		    fila.title = 'Ver el detalle de ' + entidad;
		    fila.addEventListener('click', function() { mostrarDetalle(entidad, fila); });
		  }
		  entidadesConocidas = nombres;
		  pintarEntidades();
		}

		// La lista de entidades, si no la tenemos ya de un DESC sin argumentos: se pide una sola vez.
		// Si no se puede, la grilla simplemente queda sin clickear: no es un error para el usuario.
		async function asegurarEntidades() {
		  if (entidadesConocidas) { return entidadesConocidas; }
		  entidadesConocidas = new Set();
		  const respuesta = await pedir('DESC', false);
		  if (!respuesta.ok || !respuesta.datos.rows) { pintarEntidades(); return entidadesConocidas; }
		  const columna = (respuesta.datos.headers || []).indexOf('ENTIDAD');
		  if (columna < 0) { pintarEntidades(); return entidadesConocidas; }
		  respuesta.datos.rows.forEach(function(fila) {
		    if (fila[columna] !== null && fila[columna] !== undefined) {
		      entidadesConocidas.add(String(fila[columna]));
		    }
		  });
		  pintarEntidades();
		  return entidadesConocidas;
		}

		// En el detalle de una entidad, las filas cuyo "TIPO JAVA" es otra entidad son las relaciones
		// @ManyToOne: clickearlas muestra el detalle de la relacionada en el panel de abajo.
		async function hacerRelacionesClickeables(cabeceras, tabla) {
		  const columnaTipo = cabeceras.indexOf('TIPO JAVA');
		  if (columnaTipo < 0) { return; }
		  const entidades = await asegurarEntidades();
		  const filas = tabla.querySelectorAll('tbody tr');
		  for (let i = 0; i < filas.length; i++) {
		    const fila = filas[i];
		    const celda = fila.cells[columnaTipo];
		    if (!celda) { continue; }
		    const destino = celda.textContent;
		    if (!entidades.has(destino)) { continue; }
		    fila.classList.add('fila-clickeable');
		    fila.title = 'Ver el detalle de ' + destino;
		    fila.addEventListener('click', function() { mostrarDetalle(destino, fila); });
		  }
		}

		// Marca la fila clickeada (en cualquiera de las dos grillas) y desmarca el resto.
		function marcarElegida(filaElegida) {
		  const filas = document.querySelectorAll('#t tbody tr, #t-detalle tbody tr');
		  filas.forEach(function(fila) { fila.classList.remove('fila-elegida'); });
		  if (filaElegida) { filaElegida.classList.add('fila-elegida'); }
		}

		// El detalle es un "DESC <Entidad>" más, contra el mismo endpoint: una lectura, así que no
		// depende de allow-writes ni puede cambiar nada. Los errores van abajo, sin tocar la lista.
		async function mostrarDetalle(entidad, filaElegida) {
		  marcarElegida(filaElegida);
		  detalleTitulo.textContent = 'Detalle de ' + entidad;
		  detalleError.hidden = true;
		  cajaDetalle.hidden = true;
		  abrirDetalle();

		  const respuesta = await pedir('DESC ' + entidad, false);
		  if (!respuesta.ok) {
		    detalleError.textContent = respuesta.datos.error || 'No se pudo traer el detalle.';
		    detalleError.hidden = false;
		    return;
		  }
		  const cabeceras = dibujarGrilla(cajaDetalle, tablaDetalle, respuesta.datos);
		  // Encadenar: desde el detalle de abajo también se puede saltar a otra relación.
		  hacerRelacionesClickeables(cabeceras, tablaDetalle);
		}

		function abrirDetalle() {
		  divisorAlto.hidden = false;
		  panelDetalle.hidden = false;
		  aplicarAlto(altoActual, false);
		}

		function cerrarDetalle() {
		  divisorAlto.hidden = true;
		  panelDetalle.hidden = true;
		}

		// Toda sentencia nueva arranca con el panel derecho limpio: si estaba partido, se cierra el
		// detalle, y se borra lo que hubiera quedado de la sentencia anterior. Así lo que se ve
		// siempre es el resultado de la última sentencia y no una mezcla de las dos.
		function resetPanelDerecho() {
		  cerrarDetalle();
		  detalleTitulo.textContent = '';
		  detalleError.textContent = '';
		  detalleError.hidden = true;
		  cajaDetalle.hidden = true;
		  tablaDetalle.textContent = '';
		  const filas = document.querySelectorAll('#t tbody tr, #t-detalle tbody tr');
		  filas.forEach(function(fila) { fila.classList.remove('fila-elegida'); });
		  cajaTabla.hidden = true;
		  tabla.textContent = '';
		  vacio.hidden = true;
		  crudo.hidden = true;
		  crudoPre.textContent = '';
		}

		function cabecerasDeFilas(filas) {
		  if (!filas || !filas.length) { return []; }
		  const salida = [];
		  for (let i = 0; i < filas[0].length; i++) { salida.push('col' + (i + 1)); }
		  return salida;
		}

		// Volver a enganchar lo que depende de las filas dibujadas. Ordenar rehace el tbody entero, así
		// que las filas viejas (con sus listeners) dejan de existir: sin esto, después de ordenar un
		// "DESC" las filas nuevas dejarían de abrir el detalle.
		function recablearFilas(tabla) {
		  if (tabla.querySelectorAll('thead th').length && tabla.__orden !== null) {
		    hacerListaClickeable(cabecerasActuales(tabla));
		  }
		}

		// Los nombres de las cabeceras dibujadas, leídos del thead (no se guardan aparte para no
		// tener el mismo dato en dos lugares que se pueden desincronizar).
		function cabecerasActuales(tabla) {
		  const celdas = tabla.querySelectorAll('thead th');
		  const salida = [];
		  for (let i = 0; i < celdas.length; i++) { salida.push(celdas[i].textContent); }
		  return salida;
		}

		// La lista de entidades se pide sola al abrir la página: es lo que llena el panel lateral.
		// Si el pedido falla, el panel queda vacío y no pasa nada más: no es un error para el usuario.
		asegurarEntidades();
		</script>
		</body>
		</html>
		""";
}
