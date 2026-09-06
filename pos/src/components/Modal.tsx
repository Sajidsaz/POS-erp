import { useEffect, type ReactNode } from "react";

export function Modal({
  children,
  onClose,
  wide,
}: {
  children: ReactNode;
  onClose?: () => void;
  wide?: boolean;
}) {
  useEffect(() => {
    if (!onClose) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div
        className={wide ? "modal" : "modal modal-wide"}
        onMouseDown={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
