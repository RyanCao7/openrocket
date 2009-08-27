package net.sf.openrocket.aerodynamics;

import static net.sf.openrocket.util.MathUtil.pow2;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.openrocket.aerodynamics.barrowman.FinSetCalc;
import net.sf.openrocket.aerodynamics.barrowman.RocketComponentCalc;
import net.sf.openrocket.rocketcomponent.Configuration;
import net.sf.openrocket.rocketcomponent.ExternalComponent;
import net.sf.openrocket.rocketcomponent.FinSet;
import net.sf.openrocket.rocketcomponent.Rocket;
import net.sf.openrocket.rocketcomponent.RocketComponent;
import net.sf.openrocket.rocketcomponent.SymmetricComponent;
import net.sf.openrocket.rocketcomponent.ExternalComponent.Finish;
import net.sf.openrocket.util.Coordinate;
import net.sf.openrocket.util.MathUtil;
import net.sf.openrocket.util.PolyInterpolator;
import net.sf.openrocket.util.Reflection;
import net.sf.openrocket.util.Test;

/**
 * An aerodynamic calculator that uses the extended Barrowman method to 
 * calculate the CP of a rocket.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */
public class BarrowmanCalculator extends AerodynamicCalculator {
	
	private static final String BARROWMAN_PACKAGE = "net.sf.openrocket.aerodynamics.barrowman";
	private static final String BARROWMAN_SUFFIX = "Calc";

	
	private Map<RocketComponent, RocketComponentCalc> calcMap = null;
	
	private double cacheDiameter = -1;
	private double cacheLength = -1;
	
	
	
	public BarrowmanCalculator() {

	}

	public BarrowmanCalculator(Configuration config) {
		super(config);
	}

	
	@Override
	public BarrowmanCalculator newInstance() {
		return new BarrowmanCalculator();
	}
	
	
	/**
	 * Calculate the CP according to the extended Barrowman method.
	 */
	@Override
	public Coordinate getCP(FlightConditions conditions, WarningSet warnings) {
		AerodynamicForces forces = getNonAxial(conditions, null, warnings);
		return forces.cp;
	}


	
	@Override
	public Map<RocketComponent, AerodynamicForces> getForceAnalysis(FlightConditions conditions,
			WarningSet warnings) {
		
		AerodynamicForces f;
		Map<RocketComponent, AerodynamicForces> map = 
			new LinkedHashMap<RocketComponent, AerodynamicForces>();
		
		// Add all components to the map
		for (RocketComponent c: configuration) {
			f = new AerodynamicForces();
			f.component = c;
			
			// Calculate CG
			f.cg = Coordinate.NUL;
			for (Coordinate coord: c.toAbsolute(c.getCG())) {
				f.cg = f.cg.average(coord);
			}

			map.put(c, f);
		}

		
		// Calculate non-axial force data
		AerodynamicForces total = getNonAxial(conditions, map, warnings);
		
		
		// Calculate friction data
		total.frictionCD = calculateFrictionDrag(conditions, map, warnings);
		total.pressureCD = calculatePressureDrag(conditions, map, warnings);
		total.baseCD = calculateBaseDrag(conditions, map, warnings);
		total.cg = getCG(0);

		total.component = rocket;
		map.put(rocket, total);
		
		
		for (RocketComponent c: map.keySet()) {
			f = map.get(c);
			if (Double.isNaN(f.baseCD) && Double.isNaN(f.pressureCD) && 
					Double.isNaN(f.frictionCD))
				continue;
			if (Double.isNaN(f.baseCD))
				f.baseCD = 0;
			if (Double.isNaN(f.pressureCD))
				f.pressureCD = 0;
			if (Double.isNaN(f.frictionCD))
				f.frictionCD = 0;
			f.CD = f.baseCD + f.pressureCD + f.frictionCD;
			f.Caxial = calculateAxialDrag(conditions, f.CD);
		}
		
		return map;
	}

	
	
	@Override
	public AerodynamicForces getAerodynamicForces(double time, FlightConditions conditions, 
			WarningSet warnings) {

		if (warnings == null)
			warnings = ignoreWarningSet;

		// Calculate non-axial force data
		AerodynamicForces total = getNonAxial(conditions, null, warnings);
		
		// Calculate friction data
		total.frictionCD = calculateFrictionDrag(conditions, null, warnings);
		total.pressureCD = calculatePressureDrag(conditions, null, warnings);
		total.baseCD = calculateBaseDrag(conditions, null, warnings);

		total.CD = total.frictionCD + total.pressureCD + total.baseCD;
		
		total.Caxial = calculateAxialDrag(conditions, total.CD);
		
		
		// Calculate CG and moments of inertia
		total.cg = this.getCG(time);
		total.longitudalInertia = this.getLongitudalInertia(time);
		total.rotationalInertia = this.getRotationalInertia(time);

		
		// Calculate pitch and yaw damping moments
		calculateDampingMoments(conditions, total);
		total.Cm -= total.pitchDampingMoment;
		total.Cyaw -= total.yawDampingMoment;
		
		
//		System.out.println("Conditions are "+conditions + " 
//		pitch rate="+conditions.getPitchRate());
//		System.out.println("Total Cm="+total.Cm+" damping effect="+
//				(12 * Math.signum(conditions.getPitchRate()) * 
//				MathUtil.pow2(conditions.getPitchRate()) /
//				MathUtil.pow2(conditions.getVelocity())));
		
//		double ef = Math.abs(12 *
//				MathUtil.pow2(conditions.getPitchRate()) /
//				MathUtil.pow2(conditions.getVelocity()));
//		
////		System.out.println("maxEffect="+maxEffect);
//		total.Cm -= 12 * Math.signum(conditions.getPitchRate()) *
//				MathUtil.pow2(conditions.getPitchRate()) /
//				MathUtil.pow2(conditions.getVelocity());
//				
//		total.Cyaw -= 0.06 * Math.signum(conditions.getYawRate()) * 
//						MathUtil.pow2(conditions.getYawRate()) /
//						MathUtil.pow2(conditions.getVelocity());
		
		return total;
	}
		
	
	
	@Override
	public AerodynamicForces getAxialForces(double time,
			FlightConditions conditions, WarningSet warnings) {

		if (warnings == null)
			warnings = ignoreWarningSet;

		AerodynamicForces total = new AerodynamicForces();
		total.zero();
		
		// Calculate friction data
		total.frictionCD = calculateFrictionDrag(conditions, null, warnings);
		total.pressureCD = calculatePressureDrag(conditions, null, warnings);
		total.baseCD = calculateBaseDrag(conditions, null, warnings);

		total.CD = total.frictionCD + total.pressureCD + total.baseCD;
		
		total.Caxial = calculateAxialDrag(conditions, total.CD);
		
		// Calculate CG and moments of inertia
		total.cg = this.getCG(time);
		total.longitudalInertia = this.getLongitudalInertia(time);
		total.rotationalInertia = this.getRotationalInertia(time);

		return total;
	}


	
	
	
	/*
	 * Perform the actual CP calculation.
	 */
	private AerodynamicForces getNonAxial(FlightConditions conditions,
			Map<RocketComponent, AerodynamicForces> map, WarningSet warnings) {

		AerodynamicForces total = new AerodynamicForces();
		total.zero();
		
		double radius = 0;      // aft radius of previous component
		double componentX = 0;  // aft coordinate of previous component
		AerodynamicForces forces = new AerodynamicForces();
		
		if (warnings == null)
			warnings = ignoreWarningSet;
		
		if (conditions.getAOA() > 17.5*Math.PI/180)
			warnings.add(new Warning.LargeAOA(conditions.getAOA()));
		
		checkCache();

		if (calcMap == null)
			buildCalcMap();
		
		for (RocketComponent component: configuration) {
			
			// Skip non-aerodynamic components
			if (!component.isAerodynamic())
				continue;
			
			// Check for discontinuities
			if (component instanceof SymmetricComponent) {
				SymmetricComponent sym = (SymmetricComponent) component;
				// TODO:LOW: Ignores other cluster components (not clusterable)
				double x = component.toAbsolute(Coordinate.NUL)[0].x;
				
				// Check for lengthwise discontinuity
				if (x > componentX + 0.0001){
					if (!MathUtil.equals(radius, 0)) {
						warnings.add(Warning.DISCONTINUITY);
						radius = 0;
					}
				}
				componentX = component.toAbsolute(new Coordinate(component.getLength()))[0].x;

				// Check for radius discontinuity
				if (!MathUtil.equals(sym.getForeRadius(), radius)) {
					warnings.add(Warning.DISCONTINUITY);
					// TODO: MEDIUM: Apply correction to values to cp and to map
				}
				radius = sym.getAftRadius();
			}
			
			// Call calculation method
			forces.zero();
			calcMap.get(component).calculateNonaxialForces(conditions, forces, warnings);
			forces.cp = component.toAbsolute(forces.cp)[0];
			forces.Cm = forces.CN * forces.cp.x / conditions.getRefLength();
//			System.out.println("  CN="+forces.CN+" cp.x="+forces.cp.x+" Cm="+forces.Cm);
			
			if (map != null) {
				AerodynamicForces f = map.get(component);
				
				f.cp = forces.cp;
				f.CNa = forces.CNa;
				f.CN = forces.CN;
				f.Cm = forces.Cm;
				f.Cside = forces.Cside;
				f.Cyaw = forces.Cyaw;
				f.Croll = forces.Croll;
				f.CrollDamp = forces.CrollDamp;
				f.CrollForce = forces.CrollForce;
			}
			
			total.cp = total.cp.average(forces.cp);
			total.CNa += forces.CNa;
			total.CN += forces.CN;
			total.Cm += forces.Cm;
			total.Cside += forces.Cside;
			total.Cyaw += forces.Cyaw;
			total.Croll += forces.Croll;
			total.CrollDamp += forces.CrollDamp;
			total.CrollForce += forces.CrollForce;
		}
		
		return total;
	}

	

	
	////////////////  DRAG CALCULATIONS  ////////////////
	
	
	private double calculateFrictionDrag(FlightConditions conditions, 
			Map<RocketComponent, AerodynamicForces> map, WarningSet set) {
		double c1=1.0, c2=1.0;
		
		double mach = conditions.getMach();
		double Re;
		double Cf;
		
		if (calcMap == null)
			buildCalcMap();

		Re = conditions.getVelocity() * configuration.getLength() / 
		conditions.getAtmosphericConditions().getKinematicViscosity();

//		System.out.printf("Re=%.3e   ", Re);
		
		// Calculate the skin friction coefficient (assume non-roughness limited)
		if (configuration.getRocket().isPerfectFinish()) {
			
//			System.out.printf("Perfect finish: Re=%f ",Re);
			// Assume partial laminar layer.  Roughness-limitation is checked later.
			if (Re < 1e4) {
				// Too low, constant
				Cf = 1.33e-2;
//				System.out.printf("constant Cf=%f ",Cf);
			} else if (Re < 5.39e5) {
				// Fully laminar
				Cf = 1.328 / Math.sqrt(Re);
//				System.out.printf("basic Cf=%f ",Cf);
			} else {
				// Transitional
				Cf = 1.0/pow2(1.50 * Math.log(Re) - 5.6) - 1700/Re;
//				System.out.printf("transitional Cf=%f ",Cf);
			}
			
			// Compressibility correction

			if (mach < 1.1) {
				// Below Re=1e6 no correction
				if (Re > 1e6) {
					if (Re < 3e6) {
						c1 = 1 - 0.1*pow2(mach)*(Re-1e6)/2e6;  // transition to turbulent
					} else {
						c1 = 1 - 0.1*pow2(mach);
					}
				}
			}
			if (mach > 0.9) {
				if (Re > 1e6) {
					if (Re < 3e6) {
						c2 = 1 + (1.0 / Math.pow(1+0.045*pow2(mach), 0.25) -1) * (Re-1e6)/2e6;
					} else {
						c2 = 1.0 / Math.pow(1+0.045*pow2(mach), 0.25);
					}
				}
			}
			
//			System.out.printf("c1=%f c2=%f\n", c1,c2);
			// Applying continuously around Mach 1
			if (mach < 0.9) {
				Cf *= c1;
			} else if (mach < 1.1) {
				Cf *= (c2 * (mach-0.9)/0.2 + c1 * (1.1-mach)/0.2);
			} else {
				Cf *= c2;
			}
			
//			System.out.printf("M=%f Cf=%f (smooth)\n",mach,Cf);
		
		} else {
			
			// Assume fully turbulent.  Roughness-limitation is checked later.
			if (Re < 1e4) {
				// Too low, constant
				Cf = 1.48e-2;
//				System.out.printf("LOW-TURB  ");
			} else {
				// Turbulent
				Cf = 1.0/pow2(1.50 * Math.log(Re) - 5.6);
//				System.out.printf("NORMAL-TURB  ");
			}
			
			// Compressibility correction
			
			if (mach < 1.1) {
				c1 = 1 - 0.1*pow2(mach);
			}
			if (mach > 0.9) {
				c2 = 1/Math.pow(1 + 0.15*pow2(mach), 0.58);
			}
			// Applying continuously around Mach 1
			if (mach < 0.9) {
				Cf *= c1;
			} else if (mach < 1.1) {
				Cf *= c2 * (mach-0.9)/0.2 + c1 * (1.1-mach)/0.2;
			} else {
				Cf *= c2;
			}
			
//			System.out.printf("M=%f, Cd=%f (turbulent)\n", mach,Cf);

		}
		
		// Roughness-limited value correction term
		double roughnessCorrection;
		if (mach < 0.9) {
			roughnessCorrection = 1 - 0.1*pow2(mach);
		} else if (mach > 1.1) {
			roughnessCorrection = 1/(1 + 0.18*pow2(mach));
		} else {
			c1 = 1 - 0.1*pow2(0.9);
			c2 = 1.0/(1+0.18 * pow2(1.1));
			roughnessCorrection = c2 * (mach-0.9)/0.2 + c1 * (1.1-mach)/0.2;
		}
		
//		System.out.printf("Cf=%.3f  ", Cf);
		
		
		/*
		 * Calculate the friction drag coefficient.
		 * 
		 * The body wetted area is summed up and finally corrected with the rocket
		 * fineness ratio (calculated in the same iteration).  The fins are corrected
		 * for thickness as we go on.
		 */
		
		double finFriction = 0;
		double bodyFriction = 0;
		double maxR=0, len=0;
		
		double[] roughnessLimited = new double[Finish.values().length];
		Arrays.fill(roughnessLimited, Double.NaN);
		
		for (RocketComponent c: configuration) {

			// Consider only SymmetricComponents and FinSets:
			if (!(c instanceof SymmetricComponent) &&
					!(c instanceof FinSet))
				continue;
			
			// Calculate the roughness-limited friction coefficient
			Finish finish = ((ExternalComponent)c).getFinish();
			if (Double.isNaN(roughnessLimited[finish.ordinal()])) {
				roughnessLimited[finish.ordinal()] = 
					0.032 * Math.pow(finish.getRoughnessSize()/configuration.getLength(), 0.2) *
					roughnessCorrection;
				
//				System.out.printf("roughness["+finish+"]=%.3f  ", 
//						roughnessLimited[finish.ordinal()]);
			}
			
			/*
			 * Actual Cf is maximum of Cf and the roughness-limited value.
			 * For perfect finish require additionally that Re > 1e6
			 */
			double componentCf;
			if (configuration.getRocket().isPerfectFinish()) {
				
				// For perfect finish require Re > 1e6
				if ((Re > 1.0e6) && (roughnessLimited[finish.ordinal()] > Cf)) {
					componentCf = roughnessLimited[finish.ordinal()];
//					System.out.printf("    rl=%f Cf=%f (perfect=%b)\n",
//					roughnessLimited[finish.ordinal()], 
//					Cf,rocket.isPerfectFinish());
					
//					System.out.printf("LIMITED  ");
				} else {
					componentCf = Cf;
//					System.out.printf("NORMAL  ");
				}
				
			} else {
				
				// For fully turbulent use simple max
				componentCf = Math.max(Cf, roughnessLimited[finish.ordinal()]);

			}
			
//			System.out.printf("compCf=%.3f  ", componentCf);
			

			

			// Calculate the friction drag:
			if (c instanceof SymmetricComponent) {
				
				SymmetricComponent s = (SymmetricComponent)c;
				
				bodyFriction += componentCf * s.getComponentWetArea();
				
				if (map != null) {
					// Corrected later
					map.get(c).frictionCD = componentCf * s.getComponentWetArea()
						/ conditions.getRefArea();
				}
				
				double r = Math.max(s.getForeRadius(), s.getAftRadius());
				if (r > maxR)
					maxR = r;
				len += c.getLength();
				
			} else if (c instanceof FinSet) {
				
				FinSet f = (FinSet)c;
				double mac = ((FinSetCalc)calcMap.get(c)).getMACLength();
				double cd = componentCf * (1 + 2*f.getThickness()/mac) *
					2*f.getFinCount() * f.getFinArea();
				finFriction += cd;
				
				if (map != null) {
					map.get(c).frictionCD = cd / conditions.getRefArea();
				}
				
			}
			
		}
		// fB may be POSITIVE_INFINITY, but that's ok for us
		double fB = (len+0.0001) / maxR;
		double correction = (1 + 1.0/(2*fB));
		
		// Correct body data in map
		if (map != null) {
			for (RocketComponent c: map.keySet()) {
				if (c instanceof SymmetricComponent) {
					map.get(c).frictionCD *= correction;
				}
			}
		}

//		System.out.printf("\n");
		return (finFriction + correction*bodyFriction) / conditions.getRefArea();
	}
	
	
	
	private double calculatePressureDrag(FlightConditions conditions,
			Map<RocketComponent, AerodynamicForces> map, WarningSet warnings) {
		
		double stagnation, base, total;
		double radius = 0;
		
		if (calcMap == null)
			buildCalcMap();
		
		stagnation = calculateStagnationCD(conditions.getMach());
		base = calculateBaseCD(conditions.getMach());
		
		total = 0;
		for (RocketComponent c: configuration) {
			if (!c.isAerodynamic())
				continue;
			
			// Pressure fore drag
			double cd = calcMap.get(c).calculatePressureDragForce(conditions, stagnation, base, 
					warnings);
			total += cd;
			
			if (map != null) {
				map.get(c).pressureCD = cd;
			}
			
			
			// Stagnation drag
			if (c instanceof SymmetricComponent) {
				SymmetricComponent s = (SymmetricComponent)c;

				if (radius < s.getForeRadius()) {
					double area = Math.PI*(pow2(s.getForeRadius()) - pow2(radius));
					cd = stagnation * area / conditions.getRefArea();
					total += cd;
					if (map != null) {
						map.get(c).pressureCD += cd;
					}
				}

				radius = s.getAftRadius();
			}
		}

		return total;
	}
	
	
	private double calculateBaseDrag(FlightConditions conditions,
			Map<RocketComponent, AerodynamicForces> map, WarningSet warnings) {
		
		double base, total;
		double radius = 0;
		RocketComponent prevComponent = null;
		
		if (calcMap == null)
			buildCalcMap();
		
		base = calculateBaseCD(conditions.getMach());
		total = 0;

		for (RocketComponent c: configuration) {
			if (!(c instanceof SymmetricComponent))
				continue;

			SymmetricComponent s = (SymmetricComponent)c;

			if (radius > s.getForeRadius()) {
				double area = Math.PI*(pow2(radius) - pow2(s.getForeRadius()));
				double cd = base * area / conditions.getRefArea();
				total += cd;
				if (map != null) {
					map.get(prevComponent).baseCD = cd;
				}
			}

			radius = s.getAftRadius();
			prevComponent = c;
		}
		
		if (radius > 0) {
			double area = Math.PI*pow2(radius);
			double cd = base * area / conditions.getRefArea();
			total += cd;
			if (map != null) {
				map.get(prevComponent).baseCD = cd;
			}
		}

		return total;
	}
	
	
	
	public static double calculateStagnationCD(double m) {
		double pressure;
		if (m <=1) {
			pressure = 1 + pow2(m)/4 + pow2(pow2(m))/40;
		} else {
			pressure = 1.84 - 0.76/pow2(m) + 0.166/pow2(pow2(m)) + 0.035/pow2(m*m*m);
		}
		return 0.85 * pressure;
	}
	
	
	public static double calculateBaseCD(double m) {
		if (m <= 1) {
			return 0.12 + 0.13 * m*m;
		} else {
			return 0.25 / m;
		}
	}
	
	
	
	private static final double[] axialDragPoly1, axialDragPoly2;
	static {
		PolyInterpolator interpolator;
		interpolator = new PolyInterpolator(
				new double[] { 0, 17*Math.PI/180 },
				new double[] { 0, 17*Math.PI/180 }
		);
		axialDragPoly1 = interpolator.interpolator(1, 1.3, 0, 0);
		
		interpolator = new PolyInterpolator(
				new double[] { 17*Math.PI/180, Math.PI/2 },
				new double[] { 17*Math.PI/180, Math.PI/2 },
				new double[] { Math.PI/2 }
		);
		axialDragPoly2 = interpolator.interpolator(1.3, 0, 0, 0, 0);
	}
	
	
	/**
	 * Calculate the axial drag from the total drag coefficient.
	 * 
	 * @param conditions
	 * @param cd
	 * @return
	 */
	private double calculateAxialDrag(FlightConditions conditions, double cd) {
		double aoa = MathUtil.clamp(conditions.getAOA(), 0, Math.PI);
		double mul;
		
//		double sinaoa = conditions.getSinAOA();
//		return cd * (1 + Math.min(sinaoa, 0.25));

		
		if (aoa > Math.PI/2)
			aoa = Math.PI - aoa;
		if (aoa < 17*Math.PI/180)
			mul = PolyInterpolator.eval(aoa, axialDragPoly1);
		else
			mul = PolyInterpolator.eval(aoa, axialDragPoly2);
			
		if (conditions.getAOA() < Math.PI/2)
			return mul * cd;
		else
			return -mul * cd;
	}
	
	
	private void calculateDampingMoments(FlightConditions conditions, 
			AerodynamicForces total) {
		
		// Calculate pitch and yaw damping moments
		if (conditions.getPitchRate() > 0.1 || conditions.getYawRate() > 0.1 || true) {
			double mul = getDampingMultiplier(conditions, total.cg.x);
			double pitch = conditions.getPitchRate();
			double yaw = conditions.getYawRate();
			double vel = conditions.getVelocity();
			
//			double Cm = total.Cm - total.CN * total.cg.x / conditions.getRefLength();
//			System.out.printf("Damping pitch/yaw, mul=%.4f pitch rate=%.4f "+
//					"Cm=%.4f / %.4f effect=%.4f aoa=%.4f\n", mul, pitch, total.Cm, Cm, 
//					-(mul * MathUtil.sign(pitch) * pow2(pitch/vel)), 
//					conditions.getAOA()*180/Math.PI);
			
			mul *= 3;   // TODO: Higher damping yields much more realistic apogee turn

//			total.Cm -= mul * pitch / pow2(vel);
//			total.Cyaw -= mul * yaw / pow2(vel);
			total.pitchDampingMoment = mul * MathUtil.sign(pitch) * pow2(pitch/vel);
			total.yawDampingMoment = mul * MathUtil.sign(yaw) * pow2(yaw/vel);
		} else {
			total.pitchDampingMoment = 0;
			total.yawDampingMoment = 0;
		}

	}
	
	// TODO: MEDIUM: Are the rotation etc. being added correctly?  sin/cos theta?

	
	private double getDampingMultiplier(FlightConditions conditions, double cgx) {
		if (cacheDiameter < 0) {
			double area = 0;
			cacheLength = 0;
			cacheDiameter = 0;
			
			for (RocketComponent c: configuration) {
				if (c instanceof SymmetricComponent) {
					SymmetricComponent s = (SymmetricComponent)c;
					area += s.getComponentPlanformArea();
					cacheLength += s.getLength();
				}
			}
			if (cacheLength > 0)
				cacheDiameter = area / cacheLength;
		}
		
		double mul;
		
		// Body
		mul = 0.275 * cacheDiameter / (conditions.getRefArea() * conditions.getRefLength());
		mul *= (MathUtil.pow4(cgx) + MathUtil.pow4(cacheLength - cgx));
		
		// Fins
		// TODO: LOW: This could be optimized a lot...
		for (RocketComponent c: configuration) {
			if (c instanceof FinSet) {
				FinSet f = (FinSet)c;
				mul += 0.6 * Math.min(f.getFinCount(), 4) * f.getFinArea() * 
						MathUtil.pow3(Math.abs(f.toAbsolute(new Coordinate(
										((FinSetCalc)calcMap.get(f)).getMidchordPos()))[0].x
										- cgx)) /
										(conditions.getRefArea() * conditions.getRefLength());
			}
		}
		
		return mul;
	}

	
	
	////////  The calculator map
	
	@Override
	protected void voidAerodynamicCache() {
		super.voidAerodynamicCache();
		
		calcMap = null;
		cacheDiameter = -1;
		cacheLength = -1;
	}
	
	
	private void buildCalcMap() {
		Iterator<RocketComponent> iterator;
		
		calcMap = new HashMap<RocketComponent, RocketComponentCalc>();

		iterator = rocket.deepIterator();
		while (iterator.hasNext()) {
			RocketComponent c = iterator.next();

			if (!c.isAerodynamic())
				continue;
			
			calcMap.put(c, (RocketComponentCalc) Reflection.construct(BARROWMAN_PACKAGE, 
					c, BARROWMAN_SUFFIX, c));
		}
	}
	
	
	
	
	public static void main(String[] arg) {
		
		PolyInterpolator interpolator;
		
		interpolator = new PolyInterpolator(
				new double[] { 0, 17*Math.PI/180 },
				new double[] { 0, 17*Math.PI/180 }
		);
		double[] poly1 = interpolator.interpolator(1, 1.3, 0, 0);
		
		interpolator = new PolyInterpolator(
				new double[] { 17*Math.PI/180, Math.PI/2 },
				new double[] { 17*Math.PI/180, Math.PI/2 },
				new double[] { Math.PI/2 }
		);
		double[] poly2 = interpolator.interpolator(1.3, 0, 0, 0, 0);
				
		
		for (double a=0; a<=180.1; a++) {
			double r = a*Math.PI/180;
			if (r > Math.PI/2)
				r = Math.PI - r;
			
			double value;
			if (r < 18*Math.PI/180)
				value = PolyInterpolator.eval(r, poly1);
			else
				value = PolyInterpolator.eval(r, poly2);
			
			System.out.println(""+a+" "+value);
		}
		
		System.exit(0);
		
		
		Rocket normal = Test.makeRocket();
		Rocket perfect = Test.makeRocket();
		normal.setPerfectFinish(false);
		perfect.setPerfectFinish(true);
		
		Configuration confNormal = new Configuration(normal);
		Configuration confPerfect = new Configuration(perfect);
		
		for (RocketComponent c: confNormal) {
			if (c instanceof ExternalComponent) {
				((ExternalComponent)c).setFinish(Finish.NORMAL);
			}
		}
		for (RocketComponent c: confPerfect) {
			if (c instanceof ExternalComponent) {
				((ExternalComponent)c).setFinish(Finish.NORMAL);
			}
		}
		
		
		confNormal.setToStage(0);
		confPerfect.setToStage(0);
		
		
		
		BarrowmanCalculator calcNormal = new BarrowmanCalculator(confNormal);
		BarrowmanCalculator calcPerfect = new BarrowmanCalculator(confPerfect);
		
		FlightConditions conditions = new FlightConditions(confNormal);
		
		for (double mach=0; mach < 3; mach += 0.1) {
			conditions.setMach(mach);

			Map<RocketComponent, AerodynamicForces> data = 
				calcNormal.getForceAnalysis(conditions, null);
			AerodynamicForces forcesNormal = data.get(normal);
			
			data = calcPerfect.getForceAnalysis(conditions, null);
			AerodynamicForces forcesPerfect = data.get(perfect);
			
			System.out.printf("%f %f %f %f %f %f %f\n",mach, 
					forcesNormal.pressureCD, forcesPerfect.pressureCD, 
					forcesNormal.frictionCD, forcesPerfect.frictionCD,
					forcesNormal.CD, forcesPerfect.CD);
		}
		
		
		
	}

}