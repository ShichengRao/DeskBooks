type DateRangeControlsProps = {
  start: string;
  end: string;
  onStart: (value: string) => void;
  onEnd: (value: string) => void;
};

export function DateRangeControls({ start, end, onStart, onEnd }: DateRangeControlsProps) {
  return (
    <>
      <DateInput label="From" value={start} onChange={onStart} />
      <DateInput label="To" value={end} onChange={onEnd} />
    </>
  );
}

function DateInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="flex items-center gap-1">
      <span className="text-ink-500">{label}</span>
      <input type="date" className="input" value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}
