# UX/UI de YERVIS — guía del agente visual

## Principio
Una memoria personal que se entiende a primera vista: menos «chat», más contexto del día. Captura visible y rápida. Apariencia premium pero no recargada. Inspirada en One UI sin copiar interfaces protegidas ni asumir APIs privadas de Samsung.

## Navegación inicial
1. **Hoy / Inicio:** resumen discreto del día, acceso de captura, pendientes próximos, fragmentos recientes.
2. **Memoria / Timeline:** entradas agrupadas por día; búsqueda y filtros; acceso a originales.
3. **Capturar:** composición de texto y voz local con estados reales de transcripción y revisión explícita.
4. **Ajustes:** tema, personalización, voz, privacidad y proveedores (los futuros controles deben ser honestos, no decorativos).
No crear una navegación de 7+ pestañas en el MVP.

## Elementos clave
- Acción principal de captura cerca del pulgar.
- Cards legibles; jerarquía tipográfica clara; fecha/hora siempre distinguibles.
- Modo oscuro/claro y colores dinámicos con contraste accesible.
- Estados vacíos con explicación y acción concreta.
- Sin dependencia de color para distinguir «pendiente», «confirmado» y «sugerencia».
- Textos de interfaz en español; cadenas en recursos para futura localización.
- Manejo de teclados, IME, TalkBack y escalado de fuente.

## Movimiento funcional
- Guardar: transición sutil a estado confirmado cuando persiste realmente.
- Voz: idle -> escuchando -> transcribiendo -> borrador editable -> guardado / error; animación que represente el estado real, no grabación ficticia.
- Timeline: cambio de fecha y expansión de detalles breve y coherente.
- Preferir APIs nativas de Compose; evitar efectos permanentes que gasten batería.
- Respetar accesibilidad/reducción de movimiento, dar alternativa no animada.

## Calidad mínima de UI
- Superficies táctiles de al menos 48 dp para controles interactivos.
- La navegación y entrada de texto funcionan con teclado en pantalla.
- Contraste legible, semántica para TalkBack y pruebas con tamaño de texto grande.
- No bloquear la captura por falta de red/IA.
- No afirmar «guardado» si los datos siguen siendo mocks.

## Prototipado
Primero usar estados y datos sintéticos con contrato estable suministrado por Codex; crear previews Compose donde sea útil. Probar en Pixel 8 API35. Validar One UI en Samsung real antes de decidir integraciones del sistema.

## Dirección visual F1.04 (2026-10-11)
- Identidad «memoria tranquila»: Material You dinámico por defecto; respaldo estático con semilla teal (~#00696B); superficies planas con elevación tonal y esquinas grandes (cards 16–24 dp).
- Roles fijos: `tertiaryContainer` = avisos de honestidad (demo/sugerencia); `secondaryContainer` = distintivos de tipo; `error` solo fallos reales.
- Tipografía sin fuentes custom: fecha `headlineSmall`; secciones `titleMedium` SemiBold; ítems `bodyLarge`; metadatos `bodyMedium`/`onSurfaceVariant`; distintivos `labelMedium`.
- Una mano (One UI): lectura arriba, interacción en el 40% inferior (FAB, guardar); objetivos ≥48 dp; márgenes laterales 16–24 dp; navegación inferior ≤4 destinos.
- Inicio/timeline: fecha colapsable (`LargeTopAppBar`), cabeceras de día sticky, detalle expandible, apertura en hoy.
- Movimiento 200–300 ms con significado (`AnimatedVisibility`, FAB según scroll); respetar reducción de animaciones del sistema; sin bucles.
- Accesibilidad: contraste ≥4.5:1; `mergeDescendants` en tarjetas para TalkBack; `contentDescription` en acciones sin texto; fontScale 200%; estados con texto+forma, no solo color.
- Detalle y justificación: `docs/IMPLEMENTACIONES/F1.04-investigacion-visual.md`.

## Fuentes de diseño
- https://developer.samsung.com/one-ui/index.html
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/ui/compose/animation/introduction
- https://developer.android.com/develop/ui/compose/accessibility

## Renovación extraordinaria UX — 15-10-2026

Esta mejora autorizada después de F4.10 **no crea una fase ni cambia las 123 tareas**. Detalle de decisiones y evidencia: [renovación visual experimental](IMPLEMENTACIONES/UX-renovacion-visual-experimental.md). No declara F6 terminada.

- Memoria tranquila sigue siendo el estilo principal. Material You está activo por defecto en API 31+; se puede elegir teal y tema sistema/claro/oscuro.
- Ajustes → Apariencia incluye Clásico YERVIS/Cristal experimental, espaciado cómodo/compacto y movimiento del sistema/reducido. Las preferencias se guardan en SharedPreferences local exclusivo, fuera de Room y de la configuración de voz.
- Cristal utiliza transparencia tonal, borde gradual y elevación ligera solo en barras y paneles destacados. Editor, lectura y evidencia permanecen sólidos. No hay backdrop blur, shaders ni bucles decorativos.
- Navegación: cuatro destinos con icono y etiqueta; la cápsula toma la altura real de la fila y admite fuente grande. La selección tiene semántica de pestaña; el movimiento respeta RTL y reducción del sistema.
- Hoy muestra únicamente recuerdos locales reales y su recuento, sin tareas o métricas inventadas. Memoria no presenta búsqueda/filtros hasta que exista su implementación real.
- Timeline y detalle usan claves y tipos estables, fechas recordadas e historial lazy. El original no se repite cuando coincide con la versión actual.
- El editor no se envuelve en animaciones de estado. Los parciales de voz no animan el tamaño del panel; permiso y revisión son adaptables y desplazables.
- Propuestas mantiene las once categorías reales y la honestidad de la demostración debug: editar/confirmar/rechazar localmente no equivale a guardar. Evidencia/fuentes se inspeccionan mediante expansión con estado accesible.
- Duraciones: navegación 200 ms, expansión localizada 180–200 ms, feedback nativo de controles. Un observador del ajuste Android actualiza la reducción de movimiento sin reiniciar.
