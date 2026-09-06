import { TopBar } from "./components/TopBar";
import { CheckoutScreen } from "./screens/CheckoutScreen";
import { LoginScreen } from "./screens/LoginScreen";
import { SetupScreen } from "./screens/SetupScreen";
import { ShiftGate } from "./screens/ShiftGate";
import { useApp } from "./state/AppContext";

/**
 * The POS is a small state machine, gated in order:
 *   not provisioned -> Setup ; not signed in -> Login ; no open shift -> ShiftGate ; else Checkout.
 */
export default function App() {
  const { config, principal, shift } = useApp();

  if (!config) return <SetupScreen />;
  if (!principal) return <LoginScreen />;
  if (!shift) return <ShiftGate />;

  return (
    <div className="app-shell">
      <TopBar />
      <CheckoutScreen />
    </div>
  );
}
