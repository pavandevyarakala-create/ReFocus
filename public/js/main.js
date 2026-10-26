document.addEventListener('DOMContentLoaded', () => {
    // 1. Intersection Observer for Subtle Scroll Reveals
    const revealElements = document.querySelectorAll('.reveal');
    
    // Performance optimization: disconnect observer when done if possible, or use a clean threshold.
    const revealOptions = {
        threshold: 0.1,
        rootMargin: "0px 0px -20px 0px"
    };

    const revealObserver = new IntersectionObserver((entries, observer) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('active');
                // Stop observing once revealed to improve performance
                observer.unobserve(entry.target);
            }
        });
    }, revealOptions);

    revealElements.forEach(el => {
        revealObserver.observe(el);
    });

    // 2. Accessible FAQ Accordion Logic
    const faqBtns = document.querySelectorAll('.faq-btn');
    
    faqBtns.forEach(btn => {
        btn.addEventListener('click', function() {
            const item = this.parentElement;
            const content = this.nextElementSibling;
            const isExpanded = this.getAttribute('aria-expanded') === 'true';
            
            // Close all other open items for a cleaner UX
            document.querySelectorAll('.faq-item.active').forEach(activeItem => {
                if (activeItem !== item) {
                    activeItem.classList.remove('active');
                    const activeBtn = activeItem.querySelector('.faq-btn');
                    activeBtn.setAttribute('aria-expanded', 'false');
                    activeItem.querySelector('.faq-content').style.maxHeight = null;
                }
            });
            
            // Toggle current item
            if (isExpanded) {
                item.classList.remove('active');
                this.setAttribute('aria-expanded', 'false');
                content.style.maxHeight = null;
            } else {
                item.classList.add('active');
                this.setAttribute('aria-expanded', 'true');
                content.style.maxHeight = content.scrollHeight + 'px';
            }
        });
    });

    // 3. Floating Nav State on Scroll
    const navPill = document.querySelector('.nav-container');
    
    window.addEventListener('scroll', () => {
        if (window.scrollY > 50) {
            navPill.style.boxShadow = 'var(--shadow-md)';
        } else {
            navPill.style.boxShadow = 'none';
        }
    }, { passive: true }); // passive listener for scroll performance

    // 4. Accessible Mobile Menu Toggle
    const mobileMenuBtn = document.querySelector('.mobile-menu-btn');
    const mobileMenu = document.getElementById('mobile-menu');

    mobileMenuBtn.addEventListener('click', () => {
        const isExpanded = mobileMenuBtn.getAttribute('aria-expanded') === 'true';
        
        if (isExpanded) {
            mobileMenu.style.display = 'none';
            mobileMenuBtn.setAttribute('aria-expanded', 'false');
        } else {
            mobileMenu.style.display = 'flex';
            mobileMenuBtn.setAttribute('aria-expanded', 'true');
        }
    });

    // Close mobile menu when clicking a link
    mobileMenu.querySelectorAll('a').forEach(link => {
        link.addEventListener('click', () => {
            mobileMenu.style.display = 'none';
            mobileMenuBtn.setAttribute('aria-expanded', 'false');
        });
    });
});

