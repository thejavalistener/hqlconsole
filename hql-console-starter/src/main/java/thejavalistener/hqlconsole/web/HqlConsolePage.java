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

	public static String html(String base,String path,int maxRows,boolean allowWrites)
	{
		return TEMPLATE
				.replace("__BASE__",_jsString(base))
				.replace("__PATH__",path)
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
		  body { margin:0; padding:16px; font-family: system-ui, -apple-system, "Segoe UI", sans-serif; background:var(--fondo); }
		  h1 { font-size:16px; margin:0; }
		  .barra { display:flex; align-items:baseline; gap:10px; flex-wrap:wrap; }
		  .ruta { font-family: ui-monospace, Consolas, monospace; font-size:12px; opacity:.6; }
		  .aviso { font-size:12px; color:var(--error); }
		  textarea { width:100%; min-height:120px; margin-top:10px; padding:10px; font-family: ui-monospace, Consolas, monospace; font-size:13px; line-height:1.5; border:1px solid var(--borde); border-radius:6px; resize:vertical; tab-size:2; }
		  .acciones { display:flex; align-items:center; gap:12px; margin-top:10px; flex-wrap:wrap; }
		  button { padding:7px 16px; font-size:13px; font-weight:600; color:#fff; background:var(--acento); border:0; border-radius:6px; cursor:pointer; }
		  button:disabled { opacity:.5; cursor:progress; }
		  .estado { font-size:12px; opacity:.75; }
		  .sel { color:var(--acento); opacity:1; font-weight:600; }
		  #error { display:none; margin-top:12px; padding:10px; border:1px solid var(--error); border-left-width:4px; border-radius:6px; background:#fff5f4; color:var(--error); font-size:13px; }
		  #error pre { margin:6px 0 0; font-family: ui-monospace, Consolas, monospace; font-size:12px; white-space:pre-wrap; }
		  .tabla { margin-top:12px; overflow:auto; max-height:60vh; border:1px solid var(--borde); border-radius:6px; background:#fff; }
		  table { border-collapse:collapse; width:100%; font-size:13px; }
		  th, td { padding:6px 10px; text-align:left; border-bottom:1px solid var(--borde); font-family: ui-monospace, Consolas, monospace; white-space:pre; }
		  th { position:sticky; top:0; background:#eceff3; font-weight:600; }
		  tr:nth-child(even) td { background:#fafbfc; }
		  td.nulo { color:#8b949e; font-style:italic; }
		  .pie { font-size:12px; opacity:.7; margin-top:6px; }
		  details { margin-top:10px; font-size:12px; }
		  summary { cursor:pointer; opacity:.7; }
		  details pre { background:#fff; border:1px solid var(--borde); border-radius:6px; padding:10px; overflow:auto; max-height:40vh; }
		</style>
		</head>
		<body>
		<div class="barra">
		  <h1>HQL Console</h1>
		  <span class="ruta">__PATH__</span>
		  <span class="aviso">herramienta de desarrollo: ejecuta HQL contra el EntityManager vivo</span>
		</div>
		<textarea id="hql" spellcheck="false" placeholder="SELECT e.id, e.nombre FROM Empleado e"></textarea>
		<div class="acciones">
		  <button id="run">Ejecutar</button>
		  <span class="estado">Ctrl+Enter: ejecuta todo el texto, o sólo lo seleccionado si hay algo pintado</span>
		  <span class="estado" id="estado"></span>
		  <span class="estado sel" id="seleccion" hidden></span>
		</div>
		<div id="error"><div id="error-msg"></div><pre id="error-sql"></pre></div>
		<div class="tabla" id="tabla" hidden><table id="t"></table></div>
		<div class="pie" id="pie"></div>
		<details id="crudo" hidden><summary>JSON crudo</summary><pre id="crudo-pre"></pre></details>
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
		const pie = document.getElementById('pie');
		const crudo = document.getElementById('crudo');
		const crudoPre = document.getElementById('crudo-pre');

		const EJEMPLO = 'SELECT e.id, e.nombre, e.salario FROM Empleado e';
		const guardada = localStorage.getItem('hql-console.consulta');
		ta.value = guardada ? guardada : EJEMPLO;

		// Lo que se ejecuta: la selección si hay algo pintado, y si no todo el texto.
		function textoAejecutar(valor, inicio, fin) {
		  if (inicio < fin) { return valor.substring(inicio, fin); }
		  return valor;
		}

		function refrescarSeleccion() {
		  const caracteres = ta.selectionEnd - ta.selectionStart;
		  if (caracteres > 0) {
		    seleccion.textContent = 'se ejecutará sólo la selección (' + caracteres + ' caracteres)';
		    seleccion.hidden = false;
		  } else {
		    seleccion.hidden = true;
		  }
		}

		btn.addEventListener('click', ejecutar);
		ta.addEventListener('keydown', function(ev) {
		  if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); ejecutar(); }
		});
		['keyup', 'mouseup', 'select', 'input'].forEach(function(evento) {
		  ta.addEventListener(evento, refrescarSeleccion);
		});
		document.addEventListener('selectionchange', function() {
		  if (document.activeElement === ta) { refrescarSeleccion(); }
		});
		refrescarSeleccion();

		async function ejecutar() {
		  const hql = textoAejecutar(ta.value, ta.selectionStart, ta.selectionEnd).trim();
		  if (!hql) {
		    mostrarError({ error: 'No hay nada que ejecutar: el área está vacía o la selección no tiene texto.' });
		    return;
		  }
		  // Se guarda el contenido del editor, no lo ultimo ejecutado: es el estado del textarea.
		  localStorage.setItem('hql-console.consulta', ta.value);
		  btn.disabled = true;
		  cajaError.style.display = 'none';
		  estado.textContent = 'Ejecutando...';
		  try {
		    const respuesta = await fetch(BASE + '/api/execute', {
		      method: 'POST',
		      headers: { 'Content-Type': 'application/json' },
		      body: JSON.stringify({ hql: hql })
		    });
		    let datos;
		    try {
		      datos = await respuesta.json();
		    } catch (e) {
		      datos = { error: 'El servidor respondio HTTP ' + respuesta.status + ' sin JSON.' };
		    }
		    if (!respuesta.ok) { mostrarError(datos); return; }
		    mostrarResultado(datos);
		  } catch (e) {
		    mostrarError({ error: 'No se pudo contactar la consola: ' + e });
		  } finally {
		    btn.disabled = false;
		  }
		}

		function mostrarError(datos) {
		  errorMsg.textContent = datos.error || 'Error desconocido.';
		  errorSql.textContent = datos.cause ? ('causa: ' + datos.cause) : (datos.statement || '');
		  cajaError.style.display = 'block';
		  cajaTabla.hidden = true;
		  crudo.hidden = true;
		  estado.textContent = '';
		  pie.textContent = '';
		}

		function mostrarResultado(datos) {
		  crudoPre.textContent = JSON.stringify(datos, null, 2);
		  crudo.hidden = false;

		  if (datos.type === 'DML') {
		    cajaTabla.hidden = true;
		    pie.textContent = '';
		    const detalle = datos.message ? datos.message : (datos.affectedRows + ' fila(s) afectada(s)');
		    estado.textContent = detalle + ' en ' + datos.elapsedMs + ' ms';
		    return;
		  }

		  const cabeceras = (datos.headers && datos.headers.length) ? datos.headers : cabecerasDeFilas(datos.rows);
		  tabla.textContent = '';

		  const thead = document.createElement('thead');
		  const filaCabeceras = document.createElement('tr');
		  cabeceras.forEach(function(c) {
		    const th = document.createElement('th');
		    th.textContent = c;
		    filaCabeceras.appendChild(th);
		  });
		  thead.appendChild(filaCabeceras);
		  tabla.appendChild(thead);

		  const tbody = document.createElement('tbody');
		  (datos.rows || []).forEach(function(fila) {
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
		  cajaTabla.hidden = false;

		  let resumen = datos.rowCount + ' fila' + (datos.rowCount === 1 ? '' : 's') + ' en ' + datos.elapsedMs + ' ms';
		  if (datos.truncated) { resumen = resumen + ' - truncado a ' + MAX_ROWS + ' filas'; }
		  if (datos.message) { resumen = resumen + ' - ' + datos.message; }
		  estado.textContent = resumen;
		  pie.textContent = ALLOW_WRITES
		    ? 'escrituras habilitadas (hql-console.allow-writes=true)'
		    : 'solo lectura (hql-console.allow-writes=false)';
		}

		function cabecerasDeFilas(filas) {
		  if (!filas || !filas.length) { return []; }
		  const salida = [];
		  for (let i = 0; i < filas[0].length; i++) { salida.push('col' + (i + 1)); }
		  return salida;
		}
		</script>
		</body>
		</html>
		""";
}
