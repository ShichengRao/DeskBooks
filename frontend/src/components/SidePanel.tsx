import { useEffect, type ReactNode } from "react";
import clsx from "clsx";

export function SidePanel({
  title,
  onClose,
  onSubmit,
  children,
  maxWidth = "max-w-2xl",
}: {
  title: string;
  onClose: () => void;
  onSubmit?: () => void;
  children: ReactNode;
  maxWidth?: string;
}) {
  // Esc closes the panel — every editor in the app uses this shell.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const inner = (
    <>
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold">{title}</h2>
        <button type="button" className="btn-ghost" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>
      {children}
    </>
  );

  return (
    <div
      className="fixed inset-0 bg-ink-900/40 flex items-stretch justify-end z-50"
      onClick={(e) => {
        // Click on the backdrop closes; clicks inside the panel don't.
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className={clsx("bg-white w-full h-full overflow-y-auto p-6 shadow-xl", maxWidth)}>
        {onSubmit ? (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              onSubmit();
            }}
          >
            {inner}
          </form>
        ) : (
          inner
        )}
      </div>
    </div>
  );
}
