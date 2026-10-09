/** The marigold "अ" brand mark from the prototype. Decorative: the name carries the meaning. */
export function BrandMark({ size = 34 }: { size?: number }) {
  return (
    <span
      className="brand-mark"
      aria-hidden="true"
      style={size === 34 ? undefined : { width: size, height: size, fontSize: Math.round(size * 0.56) }}
    >
      अ
    </span>
  );
}

export function Brand({ name, className }: { name: string; className?: string }) {
  return (
    <div className={`brand ${className ?? ""}`}>
      <BrandMark />
      <span className="brand-name">{name}</span>
    </div>
  );
}
