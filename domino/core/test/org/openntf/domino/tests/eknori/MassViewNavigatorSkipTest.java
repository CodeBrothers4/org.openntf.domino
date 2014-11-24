package org.openntf.domino.tests.eknori;

/*
 * Skipping: 1.000
 *  
 -- START --
 1000000
 CacheSize: 1
 -- STOP --
 Thread MassViewEntryCollectionTest elapsed time: 166953ms
 Thread MassViewEntryCollectionTest auto-recycled 987406 lotus references during run. Then recycled 12597 lotus references on completion and had 0 recycle errors

 Skipping: 1.000.000 

 -- START --
 1000000
 CacheSize: 1
 -- STOP --
 Thread MassViewEntryCollectionTest elapsed time: 169969ms
 Thread MassViewEntryCollectionTest auto-recycled 978182 lotus references during run. Then recycled 21821 lotus references on completion and had 0 recycle errors

 ?? seems not to have any effect.

 */
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openntf.domino.Database;
import org.openntf.domino.Session;
import org.openntf.domino.View;
import org.openntf.domino.ViewEntry;
import org.openntf.domino.ViewNavigator;
import org.openntf.domino.junit.DominoJUnitRunner;
import org.openntf.domino.utils.Factory;
import org.openntf.domino.utils.Factory.SessionType;

@RunWith(DominoJUnitRunner.class)
public class MassViewNavigatorSkipTest {

	private static final String TARGET = "target.nsf";
	private static final String VIEW = "Persons";

	@Test
	public void run() {

		Session s = Factory.getSession(SessionType.CURRENT);
		Database source = s.getDatabase("", TARGET, true);
		View view = source.getView(VIEW);
		System.out.println("-- START --");
		long start = System.nanoTime();

		if (null != view) {
			view.setAutoUpdate(false);

			System.out.println(view.getEntryCount());

			ViewNavigator nav = view.createViewNav();
			// nav.setCacheSize(400);
			nav.skip(1000000);

			System.out.println("CacheSize: " + nav.getCacheSize());

			view.setAutoUpdate(true);
			ViewEntry entry = null;
			entry = nav.getFirst();
			while (null != entry) {
				entry = nav.getNext(entry);
			}
		}

		long elapsed = System.nanoTime() - start;
		System.out.println("-- STOP --");
		System.out.println("Thread " + Thread.currentThread().getName() + " elapsed time: " + elapsed / 1000000 + "ms");

	}

}
