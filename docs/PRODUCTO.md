# Definición de producto — YERVIS

## Visión
Asistente personal de memoria en Android/Samsung. El usuario habla o escribe de manera natural, YERVIS conserva la información original, propone una organización útil, permite recuperarla por fechas/contextos y actúa con autorización. La interfaz debe sentirse moderna y ligera, no como un bloc de notas con carpetas.

**Problema:** pensamientos, avances, citas y tareas se pierden porque capturarlos, clasificarlos y encontrarlos exige esfuerzo.  
**Promesa:** «Dilo o escríbelo una vez; YERVIS te ayuda a encontrarlo y recordarlo después».

## Trabajos principales del usuario
1. Capturar una idea o compromiso en segundos, por texto o voz.
2. Consultar qué ocurrió/qué dijo en un día determinado.
3. Encontrar tareas, decisiones y proyectos sin organizar carpetas manualmente.
4. Preguntar con lenguaje natural y obtener respuestas respaldadas por entradas originales.
5. Corregir, borrar y controlar inferencias, notificaciones y uso de Internet.

## Tipos de información prioritarios
- Entrada original de diario (siempre preservada).
- **Rutina y hábito:** patrón repetido o práctica que la persona declara querer mantener. Una coincidencia semanal observada es una hipótesis con evidencia, no una rutina confirmada; no programar alarmas por detectar repetición.
- **Evento/cita:** suceso con fecha/hora o duración, zona horaria, ubicación y personas si fueron mencionadas. Distinguir lo confirmado de una propuesta e indicar qué parte de la fecha es incierta.
- **Tarea/compromiso:** acción concreta, estado, responsable si está claro, vencimiento separado de la hora prevista, prioridad/plazo solo cuando se expresen y relación con un evento o proyecto si existe evidencia.
- **Recordatorio/alarma:** instrucción explícita para avisar en un momento o antes de un evento/rutina. Es una acción separada del recuerdo; requiere hora/condición entendible y consentimiento. Una idea o fecha mencionada no crea una alarma por sí sola.
- **Idea/reflexión:** conservar como material recuperable, sin convertirla automáticamente en tarea ni imponer recordatorios.
- **Hecho, decisión, objetivo y progreso:** hechos con fuente; decisiones con razones y alternativas cuando se expresen; objetivos con horizonte y avance; bloqueos, dependencias y próximos pasos solo si están respaldados.
- **Contexto:** personas, proyectos, lugares, temas y recursos relacionados mediante evidencia, sin inferir perfil laboral, salud u otros atributos sensibles.

Las rutinas son prioritarias para la agenda inteligente semanal. La app podrá mostrar hábitos o secuencias repetidas detectadas a partir de varias capturas en un intervalo elegido, con las fechas y frases que sustentan la observación. La persona decide si es rutina, si quiere seguimiento y cuándo; cambios de horario, vacaciones, saltos y excepciones deben poder corregirse. No asumir que una omisión significa incumplimiento.

## Principios de interacción
- Voz primero, pero texto como alternativa completa.
- Fricción mínima: un acto de captura; confirmación solo ante consecuencias o ambigüedad importante.
- Nunca tratar conjeturas de IA como hechos: fuente visible y corrección sencilla.
- Diferenciar evento, tarea recurrente, rutina, hábito y recordatorio; recurrencia no implica alarma.
- Extraer solo lo útil para agenda y recuperación; guardar incertidumbre por campo y enlazar cada propuesta a la frase fuente.
- Las funciones básicas siguen operando sin conexión y sin proveedor de IA.
- Estados honestos: «guardado localmente», «pendiente de analizar», «sugerencia de IA».
- Respuestas habladas opcionales, discretas y desactivables.

## Producto inicial (MVP)
- Captura por texto; captura por voz y transcripción offline cuando validada.
- Línea temporal agrupada por fecha; vista detalle, editar y borrar.
- Extracción estructurada opcional con revisión del usuario; lista de pendientes.
- Búsqueda por texto y fechas; TTS breve opcional.

## Fuera de alcance inicial
- Escuchar permanentemente «Oye YERVIS» con pantalla apagada.

> Nombre de producto temporal desde 2026-10-14. El identificador Android, los paquetes y la identidad de la base Room siguen siendo los heredados para conservar compatibilidad con los datos existentes.
- Integración privilegiada con Bixby/Samsung o control arbitrario de otras apps.
- Servidor remoto obligatorio, backend cloud, monetización o cuentas.
- Diagnósticos personales, análisis invasivos, agentes que actúan sin confirmación.

## Criterios cualitativos
Una nota nunca debe desaparecer por un fallo de IA o red; las tareas no se inventan; las respuestas indican qué registros las respaldan; la UI prioriza recuperar información sobre mostrar un chat continuo.

## Datos abiertos para investigación futura
Modelo de Samsung real (RAM y One UI), motor offline STT compatible, proveedor IA con política de datos aceptable, nivel de cifrado y backup, grado de personalización de asistente.
