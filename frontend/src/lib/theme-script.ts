export const THEME_STORAGE_KEY = "akshara.theme";
const LANG_KEY = "akshara.lang"; // keep in sync with LANG_STORAGE_KEY in i18n

/**
 * Runs before first paint (inline in the root layout) so a saved theme never flashes.
 * Without a saved choice the CSS follows prefers-color-scheme.
 */
export const THEME_INIT_SCRIPT = `(function(){try{var d=document.documentElement;var t=localStorage.getItem("${THEME_STORAGE_KEY}");if(t==="light"||t==="dark")d.setAttribute("data-theme",t);var l=localStorage.getItem("${LANG_KEY}");if(l==="hi"||l==="en")d.lang=l}catch(e){}})()`;
