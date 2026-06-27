import { Component, type ErrorInfo, type ReactNode } from "react";

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error("App error:", error, info.componentStack);
  }

  reset = () => this.setState({ error: null });

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="p-8 max-w-2xl mx-auto">
        <div className="card p-6">
          <h1 className="text-lg font-semibold mb-2">Something broke.</h1>
          <p className="text-sm text-ink-600 mb-3">
            The app hit an unhandled error. The rest of your data is fine — try reloading,
            or click below to dismiss and continue.
          </p>
          <pre className="text-xs bg-ink-50 p-3 rounded overflow-x-auto whitespace-pre-wrap">
            {error.message}
          </pre>
          <div className="mt-4 flex gap-2">
            <button className="btn-primary" onClick={this.reset}>
              Dismiss
            </button>
            <button className="btn" onClick={() => location.reload()}>
              Reload
            </button>
          </div>
        </div>
      </div>
    );
  }
}
