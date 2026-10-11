# UX/UI de YEZVI — guía del agente visual

## Principio
Una memoria personal que se entiende a primera vista: menos «chat», más contexto del día. Captura visible y rápida. Apariencia premium pero no recargada. Inspirada en One UI sin copiar interfaces protegidas ni asumir APIs privadas de Samsung.

## Navegación inicial
1. **Hoy / Inicio:** resumen discreto del día, acceso de captura, pendientes próximos, fragmentos recientes.
2. **Memoria / Timeline:** entradas agrupadas por día; búsqueda y filtros; acceso a originales.
3. **Capturar:** composición de texto; más adelante micrófono/estado de transcripción.
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

## Fuentes de diseño
- https://developer.samsung.com/one-ui/index.html
- https://developer.android.com/develop/ui/compose/designsystems/material3
- https://developer.android.com/develop/ui/compose/animation/introduction
- https://developer.android.com/develop/ui/compose/accessibility
